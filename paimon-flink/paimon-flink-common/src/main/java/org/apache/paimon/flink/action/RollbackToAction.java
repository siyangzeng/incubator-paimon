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

package org.apache.paimon.flink.action;

import org.apache.paimon.table.DataTable;

import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.MultipleParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.apache.paimon.flink.action.Action.getTablePath;

/** Rollback to specific snapshot action for Flink. */
public class RollbackToAction extends TableActionBase {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackToAction.class);

    private final long snapshotId;

    public RollbackToAction(
            String warehouse, String databaseName, String tableName, long snapshotId) {
        super(warehouse, databaseName, tableName);
        this.snapshotId = snapshotId;
    }

    public static Optional<Action> create(String[] args) {
        LOG.info("rollback-to action args: {}", String.join(" ", args));

        MultipleParameterTool params = MultipleParameterTool.fromArgs(args);

        if (params.has("help")) {
            printHelp();
            return Optional.empty();
        }

        Tuple3<String, String, String> tablePath = getTablePath(params);

        if (tablePath == null) {
            throw new IllegalArgumentException("Please specific table.");
        }

        String snapshot = params.get("snapshot");
        if (snapshot == null) {
            throw new IllegalArgumentException("Please specific snapshot.");
        }

        RollbackToAction action =
                new RollbackToAction(
                        tablePath.f0, tablePath.f1, tablePath.f2, Long.parseLong(snapshot));

        return Optional.of(action);
    }

    private static void printHelp() {
        System.out.println("Action \"rollback-to\" roll back a table to a specific snapshot ID.");
        System.out.println();

        System.out.println("Syntax:");
        System.out.println(
                "  rollback-to --warehouse <warehouse-path> --database <database-name> "
                        + "--table <table-name> --snapshot <snapshot_spec>");
        System.out.println();
    }

    @Override
    public void run() throws Exception {
        LOG.debug("Run rollback-to action with snapshot id '{}'.", snapshotId);

        if (!(table instanceof DataTable)) {
            throw new IllegalArgumentException("Unknown table: " + identifier);
        }

        table.rollbackTo(snapshotId);
    }
}
