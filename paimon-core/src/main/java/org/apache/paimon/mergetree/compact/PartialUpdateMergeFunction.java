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

package org.apache.paimon.mergetree.compact;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.KeyValue;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.sink.SequenceGenerator;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.Projection;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.utils.InternalRowUtils.createFieldGetters;

/**
 * A {@link MergeFunction} where key is primary key (unique) and value is the partial record, update
 * non-null fields on merge.
 */
public class PartialUpdateMergeFunction implements MergeFunction<KeyValue> {

    public static final String FIELDS = "fields";
    public static final String SEQUENCE_GROUP = "sequence-group";

    private final InternalRow.FieldGetter[] getters;
    private final boolean ignoreDelete;
    private final Map<Integer, SequenceGenerator> fieldSequences;

    private KeyValue latestKv;
    private GenericRow row;
    private KeyValue reused;

    protected PartialUpdateMergeFunction(
            InternalRow.FieldGetter[] getters,
            boolean ignoreDelete,
            Map<Integer, SequenceGenerator> fieldSequences) {
        this.getters = getters;
        this.ignoreDelete = ignoreDelete;
        this.fieldSequences = fieldSequences;
    }

    @Override
    public void reset() {
        this.latestKv = null;
        this.row = new GenericRow(getters.length);
    }

    @Override
    public void add(KeyValue kv) {
        if (kv.valueKind() == RowKind.UPDATE_BEFORE || kv.valueKind() == RowKind.DELETE) {
            if (ignoreDelete) {
                return;
            }

            if (kv.valueKind() == RowKind.UPDATE_BEFORE) {
                throw new IllegalArgumentException(
                        "Partial update can not accept update_before records, it is a bug.");
            }

            throw new IllegalArgumentException(
                    "Partial update can not accept delete records. Partial delete is not supported!");
        }

        latestKv = kv;
        if (fieldSequences.isEmpty()) {
            updateNonNullFields(kv);
        } else {
            updateWithSequenceGroup(kv);
        }
    }

    private void updateNonNullFields(KeyValue kv) {
        for (int i = 0; i < getters.length; i++) {
            Object field = getters[i].getFieldOrNull(kv.value());
            if (field != null) {
                row.setField(i, field);
            }
        }
    }

    private void updateWithSequenceGroup(KeyValue kv) {
        for (int i = 0; i < getters.length; i++) {
            Object field = getters[i].getFieldOrNull(kv.value());
            SequenceGenerator sequenceGen = fieldSequences.get(i);
            if (sequenceGen == null) {
                if (field != null) {
                    row.setField(i, field);
                }
            } else {
                Long currentSeq = sequenceGen.generateNullable(kv.value());
                if (currentSeq != null) {
                    Long previousSeq = sequenceGen.generateNullable(row);
                    if (previousSeq == null || currentSeq >= previousSeq) {
                        row.setField(i, field);
                    }
                }
            }
        }
    }

    @Override
    @Nullable
    public KeyValue getResult() {
        if (latestKv == null) {
            if (ignoreDelete) {
                return null;
            }

            throw new IllegalArgumentException(
                    "Trying to get result from merge function without any input. This is unexpected.");
        }

        if (reused == null) {
            reused = new KeyValue();
        }
        return reused.replace(latestKv.key(), latestKv.sequenceNumber(), RowKind.INSERT, row);
    }

    public static MergeFunctionFactory<KeyValue> factory(Options options, RowType rowType) {
        return new Factory(options, rowType);
    }

    private static class Factory implements MergeFunctionFactory<KeyValue> {

        private static final long serialVersionUID = 1L;

        private final boolean ignoreDelete;
        private final List<DataType> tableTypes;
        private final Map<Integer, SequenceGenerator> fieldSequences;

        private Factory(Options options, RowType rowType) {
            this.ignoreDelete = options.get(CoreOptions.PARTIAL_UPDATE_IGNORE_DELETE);
            this.tableTypes = rowType.getFieldTypes();

            List<String> fieldNames = rowType.getFieldNames();
            this.fieldSequences = new HashMap<>();
            for (Map.Entry<String, String> entry : options.toMap().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k.startsWith(FIELDS) && k.endsWith(SEQUENCE_GROUP)) {
                    String sequenceFieldName =
                            k.substring(
                                    FIELDS.length() + 1, k.length() - SEQUENCE_GROUP.length() - 1);
                    SequenceGenerator sequenceGen =
                            new SequenceGenerator(sequenceFieldName, rowType);
                    Arrays.stream(v.split(","))
                            .map(fieldNames::indexOf)
                            .forEach(
                                    field -> {
                                        if (fieldSequences.containsKey(field)) {
                                            throw new IllegalArgumentException(
                                                    String.format(
                                                            "Field %s is defined repeatedly by multiple groups: %s",
                                                            fieldNames.get(field), k));
                                        }
                                        fieldSequences.put(field, sequenceGen);
                                    });

                    // add self
                    fieldSequences.put(sequenceGen.index(), sequenceGen);
                }
            }
        }

        @Override
        public MergeFunction<KeyValue> create(@Nullable int[][] projection) {
            List<DataType> fieldTypes = tableTypes;
            if (projection != null) {
                fieldTypes = Projection.of(projection).project(tableTypes);
            }
            InternalRow.FieldGetter[] fieldGetters = createFieldGetters(fieldTypes);

            return new PartialUpdateMergeFunction(fieldGetters, ignoreDelete, fieldSequences);
        }

        @Override
        public AdjustedProjection adjustProjection(@Nullable int[][] projection) {
            // TODO implement this, just keep required fields and adjust fieldSequences too.
            return new AdjustedProjection(null, projection);
        }
    }
}
