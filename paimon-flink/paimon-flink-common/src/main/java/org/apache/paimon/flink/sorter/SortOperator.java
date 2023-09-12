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

package org.apache.paimon.flink.sorter;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.codegen.CodeGenUtils;
import org.apache.paimon.codegen.NormalizedKeyComputer;
import org.apache.paimon.codegen.RecordComparator;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.serializer.BinaryRowSerializer;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.data.serializer.InternalSerializers;
import org.apache.paimon.disk.IOManagerImpl;
import org.apache.paimon.memory.HeapMemorySegmentPool;
import org.apache.paimon.memory.MemorySegmentPool;
import org.apache.paimon.sort.BinaryExternalSortBuffer;
import org.apache.paimon.sort.BinaryInMemorySortBuffer;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.MutableObjectIterator;

import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.runtime.operators.TableStreamOperator;

/** SortOperator to sort the `InternalRow`s by the `KeyType`. */
public class SortOperator extends TableStreamOperator<InternalRow>
        implements OneInputStreamOperator<InternalRow, InternalRow>, BoundedOneInput {

    private final RowType keyType;
    private final RowType rowType;
    private final long maxMemory;
    private final int pageSize;
    private final int arity;
    private final int spillSortMaxNumFiles;
    private transient BinaryExternalSortBuffer buffer;

    public SortOperator(
            RowType keyType,
            RowType rowType,
            long maxMemory,
            int pageSize,
            int spillSortMaxNumFiles) {
        this.keyType = keyType;
        this.rowType = rowType;
        this.maxMemory = maxMemory;
        this.pageSize = pageSize;
        this.arity = rowType.getFieldCount();
        this.spillSortMaxNumFiles = spillSortMaxNumFiles;
    }

    @Override
    public void open() throws Exception {
        super.open();
        initBuffer();
    }

    @VisibleForTesting
    void initBuffer() {
        InternalRowSerializer serializer = InternalSerializers.create(rowType);
        NormalizedKeyComputer normalizedKeyComputer =
                CodeGenUtils.newNormalizedKeyComputer(
                        keyType.getFieldTypes(), "MemTableKeyComputer");
        RecordComparator keyComparator =
                CodeGenUtils.newRecordComparator(keyType.getFieldTypes(), "MemTableComparator");

        MemorySegmentPool memoryPool = new HeapMemorySegmentPool(maxMemory, pageSize);

        BinaryInMemorySortBuffer inMemorySortBuffer =
                BinaryInMemorySortBuffer.createBuffer(
                        normalizedKeyComputer, serializer, keyComparator, memoryPool);

        buffer =
                new BinaryExternalSortBuffer(
                        new BinaryRowSerializer(serializer.getArity()),
                        keyComparator,
                        memoryPool.pageSize(),
                        inMemorySortBuffer,
                        new IOManagerImpl(
                                getContainingTask()
                                        .getEnvironment()
                                        .getIOManager()
                                        .getSpillingDirectoriesPaths()),
                        spillSortMaxNumFiles);
    }

    @Override
    public void endInput() throws Exception {
        if (buffer.size() > 0) {
            MutableObjectIterator<BinaryRow> iterator = buffer.sortedIterator();
            BinaryRow binaryRow = new BinaryRow(arity);
            while ((binaryRow = iterator.next(binaryRow)) != null) {
                output.collect(new StreamRecord<>(binaryRow));
            }
        }
    }

    @Override
    public void close() throws Exception {
        super.close();
        buffer.clear();
    }

    @Override
    public void processElement(StreamRecord<InternalRow> element) throws Exception {
        buffer.write(element.getValue());
    }

    @VisibleForTesting
    BinaryExternalSortBuffer getBuffer() {
        return buffer;
    }
}
