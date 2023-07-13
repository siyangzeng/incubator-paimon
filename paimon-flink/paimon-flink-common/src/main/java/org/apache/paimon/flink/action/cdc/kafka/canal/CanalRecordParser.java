/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.kafka.canal;

import org.apache.paimon.flink.action.cdc.ComputedColumn;
import org.apache.paimon.flink.action.cdc.TableNameConverter;
import org.apache.paimon.flink.action.cdc.mysql.MySqlTypeUtils;
import org.apache.paimon.flink.sink.cdc.CdcRecord;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.utils.StringUtils;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.type.TypeReference;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.node.NullNode;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAddColumn;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableDropColumnItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableChangeColumn;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableModifyColumn;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.utils.Preconditions.checkArgument;
import static org.apache.paimon.utils.Preconditions.checkNotNull;

/** Convert canal-json format string to list of {@link RichCdcMultiplexRecord}s. */
public class CanalRecordParser implements FlatMapFunction<String, RichCdcMultiplexRecord> {

    private static final String FIELD_DATABASE = "database";
    private static final String FIELD_TABLE = "table";
    private static final String FIELD_SQL = "sql";
    private static final String FIELD_MYSQL_TYPE = "mysqlType";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_OLD = "old";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_INSERT = "INSERT";
    private static final String OP_DELETE = "DELETE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final boolean caseSensitive;
    private final TableNameConverter tableNameConverter;
    private final List<ComputedColumn> computedColumns;

    private JsonNode root;
    private String databaseName;
    private String tableName;

    public CanalRecordParser(boolean caseSensitive, List<ComputedColumn> computedColumns) {
        this(caseSensitive, new TableNameConverter(caseSensitive), computedColumns);
    }

    public CanalRecordParser(boolean caseSensitive, TableNameConverter tableNameConverter) {
        this(caseSensitive, tableNameConverter, Collections.emptyList());
    }

    public CanalRecordParser(
            boolean caseSensitive,
            TableNameConverter tableNameConverter,
            List<ComputedColumn> computedColumns) {
        this.caseSensitive = caseSensitive;
        this.tableNameConverter = tableNameConverter;
        this.computedColumns = computedColumns;
    }

    @Override
    public void flatMap(String value, Collector<RichCdcMultiplexRecord> out) throws Exception {
        root = objectMapper.readValue(value, JsonNode.class);
        validateFormat();

        databaseName = root.get(FIELD_DATABASE).asText();
        tableName = tableNameConverter.convert(root.get(FIELD_TABLE).asText());

        extractRecords().forEach(out::collect);
    }

    private void validateFormat() {
        String errorMessageTemplate =
                "Didn't find '%s' node in json. Only supports canal-json format,"
                        + "please make sure your topic's format is correct.";

        checkNotNull(root.get(FIELD_DATABASE), errorMessageTemplate, FIELD_DATABASE);
        checkNotNull(root.get(FIELD_TABLE), errorMessageTemplate, FIELD_TABLE);
        checkNotNull(root.get(FIELD_TYPE), errorMessageTemplate, FIELD_TYPE);
        checkNotNull(root.get(FIELD_DATA), errorMessageTemplate, FIELD_DATA);

        if (isDdl()) {
            checkNotNull(root.get(FIELD_SQL), errorMessageTemplate, FIELD_SQL);
        } else {
            checkNotNull(root.get(FIELD_MYSQL_TYPE), errorMessageTemplate, FIELD_MYSQL_TYPE);
        }
    }

    private boolean isDdl() {
        return root.get("isDdl") != null && root.get("isDdl").asBoolean();
    }

    private List<RichCdcMultiplexRecord> extractRecords() {
        if (isDdl()) {
            return extractRecordsFromDdl();
        }

        // extract field types
        LinkedHashMap<String, String> mySqlFieldTypes = extractFieldTypesFromMySqlType();
        LinkedHashMap<String, DataType> paimonFieldTypes = new LinkedHashMap<>();
        mySqlFieldTypes.forEach(
                (name, type) -> paimonFieldTypes.put(name, toPaimonDataType(type, true)));

        // extract row kind and field values
        List<RichCdcMultiplexRecord> records = new ArrayList<>();
        String type = root.get(FIELD_TYPE).asText();
        ArrayNode data = (ArrayNode) root.get(FIELD_DATA);
        switch (type) {
            case OP_UPDATE:
                ArrayNode old =
                        root.get(FIELD_OLD) instanceof NullNode
                                ? null
                                : (ArrayNode) root.get(FIELD_OLD);
                for (int i = 0; i < data.size(); i++) {
                    Map<String, String> after = extractRow(data.get(i), mySqlFieldTypes);
                    if (old != null) {
                        Map<String, String> before = extractRow(old.get(i), mySqlFieldTypes);
                        // fields in "old" (before) means the fields are changed
                        // fields not in "old" (before) means the fields are not changed,
                        // so we just copy the not changed fields into before
                        for (Map.Entry<String, String> entry : after.entrySet()) {
                            if (!before.containsKey(entry.getKey())) {
                                before.put(entry.getKey(), entry.getValue());
                            }
                        }
                        before = caseSensitive ? before : keyCaseInsensitive(before);
                        records.add(
                                new RichCdcMultiplexRecord(
                                        new CdcRecord(RowKind.DELETE, before),
                                        paimonFieldTypes,
                                        databaseName,
                                        tableName));
                    }
                    after = caseSensitive ? after : keyCaseInsensitive(after);
                    records.add(
                            new RichCdcMultiplexRecord(
                                    new CdcRecord(RowKind.INSERT, after),
                                    paimonFieldTypes,
                                    databaseName,
                                    tableName));
                }
                break;
            case OP_INSERT:
                // fall through
            case OP_DELETE:
                for (JsonNode datum : data) {
                    Map<String, String> after = extractRow(datum, mySqlFieldTypes);
                    after = caseSensitive ? after : keyCaseInsensitive(after);
                    RowKind kind = type.equals(OP_INSERT) ? RowKind.INSERT : RowKind.DELETE;
                    records.add(
                            new RichCdcMultiplexRecord(
                                    new CdcRecord(kind, after),
                                    paimonFieldTypes,
                                    databaseName,
                                    tableName));
                }
                break;
            default:
                throw new UnsupportedOperationException("Unknown record type: " + type);
        }

        return records;
    }

    private List<RichCdcMultiplexRecord> extractRecordsFromDdl() {
        String sql = root.get(FIELD_SQL).asText();
        if (StringUtils.isEmpty(sql)) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, DataType> fieldTypes = new LinkedHashMap<>();
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, DbType.mysql);

        if (sqlStatement instanceof SQLAlterTableStatement) {
            SQLAlterTableStatement sqlAlterTableStatement = (SQLAlterTableStatement) sqlStatement;
            for (SQLAlterTableItem sqlAlterTableItem : sqlAlterTableStatement.getItems()) {
                extractFieldTypesFromAlterTableItem(sqlAlterTableItem, fieldTypes);
            }
        }

        return Collections.singletonList(
                new RichCdcMultiplexRecord(
                        CdcRecord.emptyRecord(), fieldTypes, databaseName, tableName));
    }

    private void extractFieldTypesFromAlterTableItem(
            SQLAlterTableItem sqlAlterTableItem, LinkedHashMap<String, DataType> fieldTypes) {
        if (sqlAlterTableItem instanceof SQLAlterTableAddColumn) {
            SQLAlterTableAddColumn sqlAlterTableAddColumn =
                    (SQLAlterTableAddColumn) sqlAlterTableItem;
            List<SQLColumnDefinition> columns = sqlAlterTableAddColumn.getColumns();

            for (SQLColumnDefinition column : columns) {
                fieldTypes.put(getColumnName(column), getPaimonDataType(column));
            }
        } else if (sqlAlterTableItem instanceof SQLAlterTableDropColumnItem) {
            // ignore
        } else if (sqlAlterTableItem instanceof MySqlAlterTableModifyColumn) {
            MySqlAlterTableModifyColumn mySqlAlterTableModifyColumn =
                    (MySqlAlterTableModifyColumn) sqlAlterTableItem;
            SQLColumnDefinition newColumnDefinition =
                    mySqlAlterTableModifyColumn.getNewColumnDefinition();

            fieldTypes.put(
                    getColumnName(newColumnDefinition), getPaimonDataType(newColumnDefinition));
        } else if (sqlAlterTableItem instanceof MySqlAlterTableChangeColumn) {
            MySqlAlterTableChangeColumn mySqlAlterTableChangeColumn =
                    (MySqlAlterTableChangeColumn) sqlAlterTableItem;
            SQLColumnDefinition newColumnDefinition =
                    mySqlAlterTableChangeColumn.getNewColumnDefinition();

            fieldTypes.put(
                    getColumnName(newColumnDefinition), getPaimonDataType(newColumnDefinition));
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported ALTER TABLE type: "
                            + sqlAlterTableItem.getClass().getSimpleName());
        }
    }

    private String getColumnName(SQLColumnDefinition column) {
        return toFieldName(StringUtils.replace(column.getColumnName(), "`", ""));
    }

    private DataType getPaimonDataType(SQLColumnDefinition column) {
        return toPaimonDataType(
                column.getDataType().toString(), !column.containsNotNullConstaint());
    }

    private String toFieldName(String rawName) {
        return StringUtils.caseSensitiveConversion(rawName, caseSensitive);
    }

    private DataType toPaimonDataType(String mySqlType, boolean isNullable) {
        return MySqlTypeUtils.toDataType(mySqlType).copy(isNullable);
    }

    private LinkedHashMap<String, String> extractFieldTypesFromMySqlType() {
        LinkedHashMap<String, String> fieldTypes = new LinkedHashMap<>();

        JsonNode schema = root.get(FIELD_MYSQL_TYPE);
        Iterator<String> iterator = schema.fieldNames();
        while (iterator.hasNext()) {
            String fieldName = iterator.next();
            String fieldType = schema.get(fieldName).asText();
            fieldTypes.put(toFieldName(fieldName), fieldType);
        }

        return fieldTypes;
    }

    private Map<String, String> extractRow(JsonNode record, Map<String, String> mySqlFieldTypes) {
        Map<String, Object> jsonMap =
                objectMapper.convertValue(record, new TypeReference<Map<String, Object>>() {});
        if (jsonMap == null) {
            return new HashMap<>();
        }

        Map<String, String> resultMap = new HashMap<>();
        for (Map.Entry<String, String> field : mySqlFieldTypes.entrySet()) {
            String fieldName = field.getKey();
            String mySqlType = field.getValue();
            Object objectValue = jsonMap.get(fieldName);
            if (objectValue == null) {
                continue;
            }

            String oldValue = objectValue.toString();
            String newValue = oldValue;

            if (MySqlTypeUtils.isSetType(MySqlTypeUtils.getShortType(mySqlType))) {
                newValue = CanalFieldParser.convertSet(newValue, mySqlType);
            } else if (MySqlTypeUtils.isEnumType(MySqlTypeUtils.getShortType(mySqlType))) {
                newValue = CanalFieldParser.convertEnum(newValue, mySqlType);
            } else if (MySqlTypeUtils.isGeoType(MySqlTypeUtils.getShortType(mySqlType))) {
                try {
                    byte[] wkb =
                            CanalFieldParser.convertGeoType2WkbArray(
                                    oldValue.getBytes(StandardCharsets.ISO_8859_1));
                    newValue = MySqlTypeUtils.convertWkbArray(wkb);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            String.format("Failed to convert %s to geometry JSON.", oldValue), e);
                }
            }
            resultMap.put(fieldName, newValue);
        }

        // generate values for computed columns
        for (ComputedColumn computedColumn : computedColumns) {
            resultMap.put(
                    computedColumn.columnName(),
                    computedColumn.eval(resultMap.get(computedColumn.fieldReference())));
        }

        return resultMap;
    }

    private Map<String, String> keyCaseInsensitive(Map<String, String> origin) {
        Map<String, String> keyCaseInsensitive = new HashMap<>();
        for (Map.Entry<String, String> entry : origin.entrySet()) {
            String fieldName = entry.getKey().toLowerCase();
            checkArgument(
                    !keyCaseInsensitive.containsKey(fieldName),
                    "Duplicate key appears when converting map keys to case-insensitive form. Original map is:\n%s",
                    origin);
            keyCaseInsensitive.put(fieldName, entry.getValue());
        }
        return keyCaseInsensitive;
    }
}
