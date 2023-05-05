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

import org.apache.paimon.KeyValue;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.utils.ReusingTestData;
import org.apache.paimon.utils.TestReusingRecordReader;

import org.junit.jupiter.api.RepeatedTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link LoserTree}. */
public class LoserTreeTest {
    private static final Comparator<KeyValue> KEY_COMPARATOR =
            Comparator.comparingInt(o -> o.key().getInt(0));
    private static final Comparator<KeyValue> SEQUENCE_COMPARATOR =
            Comparator.comparingLong(KeyValue::sequenceNumber);

    @RepeatedTest(100)
    public void testLoserTreeIsOrdered() throws IOException {
        List<ReusingTestData> reusingTestData = ReusingTestData.generateData(1000, false);
        List<RecordReader<KeyValue>> sortedTestReaders = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int numberReaders = random.nextInt(20) + 1;
        int lowerBound = 0, upperBound = reusingTestData.size();
        for (int i = 0; i < numberReaders; i++) {
            int subUpperBound = random.nextInt(lowerBound, upperBound);
            List<ReusingTestData> subReusingTestData =
                    reusingTestData.subList(lowerBound, subUpperBound);
            Collections.sort(subReusingTestData);
            sortedTestReaders.add(new TestReusingRecordReader(subReusingTestData));
            lowerBound = subUpperBound;
        }
        Collections.sort(reusingTestData);
        checkLoserTree(sortedTestReaders, reusingTestData);
    }

    private void checkLoserTree(
            List<RecordReader<KeyValue>> sortedTestReaders, List<ReusingTestData> expectedData)
            throws IOException {
        try (LoserTree<KeyValue> loserTree =
                new LoserTree<>(sortedTestReaders, KEY_COMPARATOR, SEQUENCE_COMPARATOR)) {
            Iterator<ReusingTestData> expectedIterator = expectedData.iterator();
            do {
                loserTree.adjustForNextLoop();
                for (KeyValue winner = loserTree.popWinner();
                        winner != null;
                        winner = loserTree.popWinner()) {
                    assertThat(expectedIterator.hasNext());
                    expectedIterator.next().assertEquals(winner);
                }
            } while (loserTree.peekWinner() != null && expectedIterator.hasNext());
        }
    }
}
