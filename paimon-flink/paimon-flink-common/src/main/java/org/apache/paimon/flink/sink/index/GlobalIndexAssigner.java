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

package org.apache.paimon.flink.sink.index;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.CoreOptions.MergeEngine;
import org.apache.paimon.codegen.RecordComparator;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.serializer.BinaryRowSerializer;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.data.serializer.RowCompactedSerializer;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.flink.RocksDBOptions;
import org.apache.paimon.flink.lookup.RocksDBStateFactory;
import org.apache.paimon.flink.lookup.RocksDBValueState;
import org.apache.paimon.memory.HeapMemorySegmentPool;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.sort.BinaryExternalSortBuffer;
import org.apache.paimon.sort.BinaryInMemorySortBuffer;
import org.apache.paimon.table.AbstractFileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.PartitionKeyExtractor;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.FileIOUtils;
import org.apache.paimon.utils.Filter;
import org.apache.paimon.utils.IDMapping;
import org.apache.paimon.utils.MutableObjectIterator;
import org.apache.paimon.utils.PositiveIntInt;
import org.apache.paimon.utils.PositiveIntIntSerializer;
import org.apache.paimon.utils.SerBiFunction;
import org.apache.paimon.utils.SerializableFunction;

import org.apache.flink.table.runtime.util.KeyValueIterator;
import org.rocksdb.RocksDBException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.apache.paimon.codegen.CodeGenUtils.newNormalizedKeyComputer;
import static org.apache.paimon.codegen.CodeGenUtils.newRecordComparator;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Assign UPDATE_BEFORE and bucket for the input record, output record with bucket. */
public class GlobalIndexAssigner<T> implements Serializable, Closeable {

    private static final long serialVersionUID = 1L;

    private static final String INDEX_NAME = "keyIndex";

    private final AbstractFileStoreTable table;
    private final SerializableFunction<TableSchema, PartitionKeyExtractor<T>> extractorFunction;
    private final SerializableFunction<TableSchema, PartitionKeyExtractor<T>>
            bootstrapExtractorFunction;
    private final SerializableFunction<T, Integer> bootstrapBucketFunction;
    private final SerBiFunction<T, BinaryRow, T> setPartition;
    private final SerBiFunction<T, RowKind, T> setRowKind;

    private transient boolean bootstrap;
    private transient BinaryExternalSortBuffer bootstrapBuffer;

    private transient int targetBucketRowNumber;
    private transient int assignId;
    private transient BiConsumer<T, Integer> collector;
    private transient int numAssigners;
    private transient PartitionKeyExtractor<T> extractor;
    private transient PartitionKeyExtractor<T> keyPartExtractor;
    private transient File path;
    private transient RocksDBStateFactory stateFactory;
    private transient RocksDBValueState<InternalRow, PositiveIntInt> keyIndex;

    private transient IDMapping<BinaryRow> partMapping;
    private transient BucketAssigner bucketAssigner;
    private transient ExistsAction existsAction;

    public GlobalIndexAssigner(
            Table table,
            SerializableFunction<TableSchema, PartitionKeyExtractor<T>> extractorFunction,
            SerializableFunction<TableSchema, PartitionKeyExtractor<T>> bootstrapExtractorFunction,
            SerializableFunction<T, Integer> bootstrapBucketFunction,
            SerBiFunction<T, BinaryRow, T> setPartition,
            SerBiFunction<T, RowKind, T> setRowKind) {
        this.table = (AbstractFileStoreTable) table;
        this.extractorFunction = extractorFunction;
        this.bootstrapExtractorFunction = bootstrapExtractorFunction;
        this.bootstrapBucketFunction = bootstrapBucketFunction;
        this.setPartition = setPartition;
        this.setRowKind = setRowKind;
    }

    // ================== Start Public API ===================

    public void open(
            IOManager ioManager,
            File tmpDir,
            int numAssigners,
            int assignId,
            BiConsumer<T, Integer> collector)
            throws Exception {
        this.numAssigners = numAssigners;
        this.assignId = assignId;
        this.collector = collector;

        CoreOptions coreOptions = table.coreOptions();
        this.targetBucketRowNumber = (int) coreOptions.dynamicBucketTargetRowNum();
        this.extractor = extractorFunction.apply(table.schema());
        this.keyPartExtractor = bootstrapExtractorFunction.apply(table.schema());

        // state
        Options options = coreOptions.toConfiguration();
        this.path = new File(tmpDir, "lookup-" + UUID.randomUUID());

        this.stateFactory =
                new RocksDBStateFactory(
                        path.toString(), options, coreOptions.crossPartitionUpsertIndexTtl());
        RowType keyType = table.schema().logicalTrimmedPrimaryKeysType();
        this.keyIndex =
                stateFactory.valueState(
                        INDEX_NAME,
                        new RowCompactedSerializer(keyType),
                        new PositiveIntIntSerializer(),
                        options.get(RocksDBOptions.LOOKUP_CACHE_ROWS));

        this.partMapping = new IDMapping<>(BinaryRow::copy);
        this.bucketAssigner = new BucketAssigner();
        this.existsAction = fromMergeEngine(coreOptions.mergeEngine());

        // create bootstrap sort buffer
        this.bootstrap = true;
        int pageSize = coreOptions.pageSize();
        long bufferSize = coreOptions.writeBufferSize() / 2;
        RecordComparator comparator =
                newRecordComparator(
                        Collections.singletonList(DataTypes.BYTES()), "binary_comparator");
        BinaryInMemorySortBuffer sortBuffer =
                BinaryInMemorySortBuffer.createBuffer(
                        newNormalizedKeyComputer(
                                Collections.singletonList(DataTypes.BYTES()),
                                "binary_normalized_key"),
                        new InternalRowSerializer(DataTypes.BYTES(), DataTypes.BYTES()),
                        comparator,
                        new HeapMemorySegmentPool(bufferSize, pageSize));
        this.bootstrapBuffer =
                new BinaryExternalSortBuffer(
                        new BinaryRowSerializer(2),
                        comparator,
                        pageSize,
                        sortBuffer,
                        ioManager,
                        coreOptions.localSortMaxNumFileHandles());
    }

    public void bootstrap(T value) throws IOException {
        checkArgument(bootstrap);
        BinaryRow partition = keyPartExtractor.partition(value);
        BinaryRow key = keyPartExtractor.trimmedPrimaryKey(value);
        int partId = partMapping.index(partition);
        int bucket = bootstrapBucketFunction.apply(value);
        bucketAssigner.bootstrapBucket(partition, bucket);
        PositiveIntInt partAndBucket = new PositiveIntInt(partId, bucket);
        bootstrapBuffer.write(
                GenericRow.of(keyIndex.serializeKey(key), keyIndex.serializeValue(partAndBucket)));
    }

    public void endBoostrap() throws IOException, RocksDBException {
        bootstrap = false;
        MutableObjectIterator<BinaryRow> iterator = bootstrapBuffer.sortedIterator();
        BinaryRow row = new BinaryRow(2);
        KeyValueIterator<byte[], byte[]> kvIter =
                new KeyValueIterator<byte[], byte[]>() {

                    private BinaryRow current;

                    @Override
                    public boolean advanceNext() {
                        try {
                            current = iterator.next(row);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return current != null;
                    }

                    @Override
                    public byte[] getKey() {
                        return current.getBinary(0);
                    }

                    @Override
                    public byte[] getValue() {
                        return current.getBinary(1);
                    }
                };

        stateFactory.bulkLoad(keyIndex.columnFamily(), kvIter);
        bootstrapBuffer.clear();
        bootstrapBuffer = null;
    }

    public void processInput(T value) throws Exception {
        checkArgument(!bootstrap);
        BinaryRow partition = extractor.partition(value);
        BinaryRow key = extractor.trimmedPrimaryKey(value);

        int partId = partMapping.index(partition);

        PositiveIntInt partitionBucket = keyIndex.get(key);
        if (partitionBucket != null) {
            int previousPartId = partitionBucket.i1();
            int previousBucket = partitionBucket.i2();
            if (previousPartId == partId) {
                collect(value, previousBucket);
            } else {
                switch (existsAction) {
                    case DELETE:
                        {
                            // retract old record
                            BinaryRow previousPart = partMapping.get(previousPartId);
                            T retract = setPartition.apply(value, previousPart);
                            retract = setRowKind.apply(retract, RowKind.DELETE);
                            collect(retract, previousBucket);
                            bucketAssigner.decrement(previousPart, previousBucket);

                            // new record
                            processNewRecord(partition, partId, key, value);
                            break;
                        }
                    case USE_OLD:
                        {
                            BinaryRow previousPart = partMapping.get(previousPartId);
                            T newValue = setPartition.apply(value, previousPart);
                            collect(newValue, previousBucket);
                            break;
                        }
                    case SKIP_NEW:
                        // do nothing
                        break;
                }
            }
        } else {
            // new record
            processNewRecord(partition, partId, key, value);
        }
    }

    @Override
    public void close() throws IOException {
        if (stateFactory != null) {
            stateFactory.close();
            stateFactory = null;
        }

        if (path != null) {
            FileIOUtils.deleteDirectoryQuietly(path);
        }
    }

    // ================== End Public API ===================

    private void processNewRecord(BinaryRow partition, int partId, BinaryRow key, T value)
            throws IOException {
        int bucket = assignBucket(partition);
        keyIndex.put(key, new PositiveIntInt(partId, bucket));
        collect(value, bucket);
    }

    private int assignBucket(BinaryRow partition) {
        return bucketAssigner.assignBucket(partition, this::isAssignBucket, targetBucketRowNumber);
    }

    private boolean isAssignBucket(int bucket) {
        return computeAssignId(bucket) == assignId;
    }

    private int computeAssignId(int hash) {
        return Math.abs(hash % numAssigners);
    }

    private void collect(T value, int bucket) {
        collector.accept(value, bucket);
    }

    private static class BucketAssigner {

        private final Map<BinaryRow, TreeMap<Integer, Integer>> stats = new HashMap<>();

        public void bootstrapBucket(BinaryRow part, int bucket) {
            TreeMap<Integer, Integer> bucketMap = bucketMap(part);
            Integer count = bucketMap.get(bucket);
            if (count == null) {
                count = 0;
            }
            bucketMap.put(bucket, count + 1);
        }

        public int assignBucket(BinaryRow part, Filter<Integer> filter, int maxCount) {
            TreeMap<Integer, Integer> bucketMap = bucketMap(part);
            for (Map.Entry<Integer, Integer> entry : bucketMap.entrySet()) {
                int bucket = entry.getKey();
                int count = entry.getValue();
                if (filter.test(bucket) && count < maxCount) {
                    bucketMap.put(bucket, count + 1);
                    return bucket;
                }
            }

            for (int i = 0; ; i++) {
                if (filter.test(i) && !bucketMap.containsKey(i)) {
                    bucketMap.put(i, 1);
                    return i;
                }
            }
        }

        public void decrement(BinaryRow part, int bucket) {
            bucketMap(part).compute(bucket, (k, v) -> v == null ? 0 : v - 1);
        }

        private TreeMap<Integer, Integer> bucketMap(BinaryRow part) {
            TreeMap<Integer, Integer> map = stats.get(part);
            if (map == null) {
                map = new TreeMap<>();
                stats.put(part.copy(), map);
            }
            return map;
        }
    }

    private ExistsAction fromMergeEngine(MergeEngine mergeEngine) {
        switch (mergeEngine) {
            case DEDUPLICATE:
                return ExistsAction.DELETE;
            case PARTIAL_UPDATE:
            case AGGREGATE:
                return ExistsAction.USE_OLD;
            case FIRST_ROW:
                return ExistsAction.SKIP_NEW;
            default:
                throw new UnsupportedOperationException("Unsupported engine: " + mergeEngine);
        }
    }

    private enum ExistsAction {
        DELETE,
        USE_OLD,
        SKIP_NEW
    }
}
