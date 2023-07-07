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

package org.apache.paimon.predicate;

import java.util.List;

/**
 * DeletePushDownPartitionKeyVisitor visit the predicate and check if it only contains partition
 * keys and can be push down.
 */
public class DeletePushDownPartitionKeyVisitor implements FunctionVisitor<Boolean> {

    private final List<String> partitionKeys;

    public DeletePushDownPartitionKeyVisitor(List<String> partitionKeys) {
        this.partitionKeys = partitionKeys;
    }

    @Override
    public Boolean visitIsNotNull(FieldRef fieldRef) {
        return false;
    }

    @Override
    public Boolean visitIsNull(FieldRef fieldRef) {
        return false;
    }

    @Override
    public Boolean visitStartsWith(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitLessThan(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitGreaterOrEqual(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitNotEqual(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitLessOrEqual(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitEqual(FieldRef fieldRef, Object literal) {
        return partitionKeys.contains(fieldRef.name());
    }

    @Override
    public Boolean visitGreaterThan(FieldRef fieldRef, Object literal) {
        return false;
    }

    @Override
    public Boolean visitIn(FieldRef fieldRef, List<Object> literals) {
        return false;
    }

    @Override
    public Boolean visitNotIn(FieldRef fieldRef, List<Object> literals) {
        return false;
    }

    @Override
    public Boolean visitAnd(List<Boolean> children) {
        return children.stream().reduce((first, second) -> first && second).get();
    }

    @Override
    public Boolean visitOr(List<Boolean> children) {
        return false;
    }
}
