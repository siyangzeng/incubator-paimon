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
package org.apache.paimon.spark.commands

import org.apache.paimon.table.{BucketMode, FileStoreTable, Table}
import org.apache.paimon.table.sink.{CommitMessage, CommitMessageSerializer}

import java.io.IOException

/** Helper trait for all paimon commands. */
trait PaimonCommand {

  val table: Table

  val BUCKET_COL = "_bucket_"

  def isDynamicBucketTable: Boolean = {
    table.isInstanceOf[FileStoreTable] &&
    table.asInstanceOf[FileStoreTable].bucketMode == BucketMode.DYNAMIC
  }

  def deserializeCommitMessage(
      serializer: CommitMessageSerializer,
      bytes: Array[Byte]): CommitMessage = {
    try {
      serializer.deserialize(serializer.getVersion, bytes)
    } catch {
      case e: IOException =>
        throw new RuntimeException("Failed to deserialize CommitMessage's object", e)
    }
  }
}
