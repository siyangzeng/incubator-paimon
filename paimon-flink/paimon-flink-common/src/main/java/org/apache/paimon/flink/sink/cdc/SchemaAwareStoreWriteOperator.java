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

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.data.GenericRow;
import org.apache.paimon.flink.sink.AbstractStoreWriteOperator;
import org.apache.paimon.flink.sink.LogSinkFunction;
import org.apache.paimon.flink.sink.StoreSinkWrite;
import org.apache.paimon.options.ConfigOption;
import org.apache.paimon.options.ConfigOptions;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.SinkRecord;

import org.apache.flink.runtime.state.StateInitializationContext;

import javax.annotation.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * An {@link AbstractStoreWriteOperator} which is aware of schema changes.
 *
 * <p>When the input {@link CdcRecord} contains a field name not in the current {@link TableSchema},
 * it periodically queries the latest schema, until the latest schema contains that field name.
 */
public class SchemaAwareStoreWriteOperator extends AbstractStoreWriteOperator<CdcRecord> {

    static final ConfigOption<Duration> RETRY_SLEEP_TIME =
            ConfigOptions.key("cdc.retry-sleep-time")
                    .durationType()
                    .defaultValue(Duration.ofMillis(500));

    private final long retrySleepMillis;

    public SchemaAwareStoreWriteOperator(
            FileStoreTable table,
            @Nullable LogSinkFunction logSinkFunction,
            StoreSinkWrite.Provider storeSinkWriteProvider) {
        super(table, logSinkFunction, storeSinkWriteProvider);
        retrySleepMillis = table.coreOptions().toConfiguration().get(RETRY_SLEEP_TIME).toMillis();
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        table = table.copyWithLatestSchema();
        super.initializeState(context);
    }

    @Override
    protected SinkRecord processRecord(CdcRecord record) throws Exception {
        Optional<GenericRow> optionalConverted = record.toGenericRow(table.schema().fields());
        if (!optionalConverted.isPresent()) {
            while (true) {
                table = table.copyWithLatestSchema();
                optionalConverted = record.toGenericRow(table.schema().fields());
                if (optionalConverted.isPresent()) {
                    break;
                }
                Thread.sleep(retrySleepMillis);
            }
            write.replace(commitUser -> table.newWrite(commitUser));
        }

        try {
            return write.write(optionalConverted.get());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
