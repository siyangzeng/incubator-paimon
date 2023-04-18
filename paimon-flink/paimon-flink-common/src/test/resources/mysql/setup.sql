-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- In production you would almost certainly limit the replication user must be on the follower (slave) machine,
-- to prevent other clients accessing the log from other machines. For example, 'replicator'@'follower.acme.com'.
-- However, in this database we'll grant the test user 'paimonuser' all privileges:
--
GRANT ALL PRIVILEGES ON *.* TO 'paimonuser'@'%';

-- ################################################################################
--  MySqlSyncTableActionITCase
-- ################################################################################

CREATE DATABASE paimon_sync_table;
USE paimon_sync_table;

CREATE TABLE schema_evolution_1 (
    pt INT,
    _id INT,
    v1 VARCHAR(10),
    PRIMARY KEY (_id)
);

CREATE TABLE schema_evolution_2 (
    pt INT,
    _id INT,
    v1 VARCHAR(10),
    PRIMARY KEY (_id)
);

CREATE TABLE schema_evolution_multiple (
    _id INT,
    v1 VARCHAR(10),
    v2 INT,
    v3 VARCHAR(10),
    PRIMARY KEY (_id)
);

CREATE TABLE all_types_table (
    _id INT,
    -- TINYINT
    _boolean TINYINT(1),
    _tinyint TINYINT,
    _tinyint_unsigned TINYINT(2) UNSIGNED,
    _tinyint_unsigned_zerofill TINYINT(2) UNSIGNED ZEROFILL,
    -- SMALLINT
    _smallint SMALLINT,
    _smallint_unsigned SMALLINT UNSIGNED,
    _smallint_unsigned_zerofill SMALLINT(4) UNSIGNED ZEROFILL,
    -- MEDIUMINT
    _mediumint MEDIUMINT,
    _mediumint_unsigned MEDIUMINT UNSIGNED,
    _mediumint_unsigned_zerofill MEDIUMINT(8) UNSIGNED ZEROFILL,
    -- INT
    _int INT,
    _int_unsigned INT UNSIGNED,
    _int_unsigned_zerofill INT(8) UNSIGNED ZEROFILL,
    -- BIGINT
    _bigint BIGINT,
    _bigint_unsigned BIGINT UNSIGNED,
    _bigint_unsigned_zerofill BIGINT(16) UNSIGNED ZEROFILL,
    _serial SERIAL,
    -- FLOAT
    _float FLOAT,
    _float_unsigned FLOAT UNSIGNED,
    _float_unsigned_zerofill FLOAT(4) UNSIGNED ZEROFILL,
    -- REAL
    _real REAL,
    _real_unsigned REAL UNSIGNED,
    _real_unsigned_zerofill REAL(10, 7) UNSIGNED ZEROFILL,
    -- DOUBLE
    _double DOUBLE,
    _double_unsigned DOUBLE UNSIGNED,
    _double_unsigned_zerofill DOUBLE(10, 7) UNSIGNED ZEROFILL,
    -- DOUBLE PRECISION
    _double_precision DOUBLE PRECISION,
    _double_precision_unsigned DOUBLE PRECISION UNSIGNED,
    _double_precision_unsigned_zerofill DOUBLE PRECISION(10, 7) UNSIGNED ZEROFILL,
    -- NUMERIC
    _numeric NUMERIC(8, 3),
    _numeric_unsigned NUMERIC(8, 3) UNSIGNED,
    _numeric_unsigned_zerofill NUMERIC(8, 3) UNSIGNED ZEROFILL,
    -- FIXED
    _fixed FIXED(40, 3),
    _fixed_unsigned FIXED(40, 3) UNSIGNED,
    _fixed_unsigned_zerofill FIXED(40, 3) UNSIGNED ZEROFILL,
    -- DECIMAL
    _decimal DECIMAL(8),
    _decimal_unsigned DECIMAL(8) UNSIGNED,
    _decimal_unsigned_zerofill DECIMAL(8) UNSIGNED ZEROFILL,
    -- DATE
    _date DATE,
    -- DATETIME
    _datetime DATETIME,
    _datetime3 DATETIME(3),
    _datetime6 DATETIME(6),
    -- DATETIME precision test
    _datetime_p DATETIME,
    _datetime_p2 DATETIME(2),
    -- TIMESTAMP
    _timestamp TIMESTAMP(6) DEFAULT NULL,
    -- string
    _char CHAR(10),
    _varchar VARCHAR(20),
    _text TEXT,
    -- BINARY
    _bin BINARY(10),
    _varbin VARBINARY(20),
    _blob BLOB,
    PRIMARY KEY (_id)
);

INSERT INTO all_types_table VALUES (
    1,
    -- TINYINT
    true, 1, 2, 3,
    -- SMALLINT
    1000, 2000, 3000,
    -- MEDIUMINT
    100000, 200000, 300000,
    -- INT
    1000000, 2000000, 3000000,
    -- BIGINT
    10000000000, 20000000000, 30000000000, 40000000000,
    -- FLOAT
    1.5, 2.5, 3.5,
    -- REAL
    1.000001, 2.000002, 3.000003,
    -- DOUBLE
    1.000011, 2.000022, 3.000033,
    -- DOUBLE PRECISION
    1.000111, 2.000222, 3.000333,
    -- NUMERIC
    12345.11, 12345.22, 12345.33,
    -- FIXED
    123456789876543212345678987654321.11, 123456789876543212345678987654321.22, 123456789876543212345678987654321.33,
    -- DECIMAL
    11111, 22222, 33333,
    -- DATE
    '2023-03-23',
    -- DATETIME
    '2023-03-23 14:30:05', '2023-03-23 14:30:05.123', '2023-03-23 14:30:05.123456',
    -- DATETIME precision test
    '2023-03-24 14:30', '2023-03-24 14:30:05.12',
    -- TIMESTAMP
    '2023-03-23 15:00:10.123456',
    -- string
    'Paimon', 'Apache Paimon', 'Apache Paimon MySQL Test Data',
    -- BINARY
    'bytes', 'more bytes', 'very long bytes test data'
), (
    2,
    NULL, NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL, 50000000000, -- SERIAL is never NULL
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL,
    NULL,
    NULL, NULL, NULL,
    NULL, NULL,
    NULL,
    NULL, NULL, NULL,
    NULL, NULL, NULL
);

CREATE TABLE incompatible_field_1 (
    _id INT,
    v1 DATETIME,
    PRIMARY KEY (_id)
);

CREATE TABLE incompatible_field_2 (
    _id INT,
    v1 INT,
    PRIMARY KEY (_id)
);

CREATE TABLE incompatible_pk_1 (
    a INT,
    b BIGINT,
    c VARCHAR(20),
    PRIMARY KEY (a, b)
);

CREATE TABLE incompatible_pk_2 (
    a INT,
    b BIGINT,
    c VARCHAR(20),
    PRIMARY KEY (a)
);

-- ################################################################################
--  MySqlSyncDatabaseActionITCase
-- ################################################################################

CREATE DATABASE paimon_sync_database;
USE paimon_sync_database;

CREATE TABLE t1 (
    k INT,
    v1 VARCHAR(10),
    PRIMARY KEY (k)
);

CREATE TABLE t2 (
    k1 INT,
    k2 VARCHAR(10),
    v1 INT,
    v2 BIGINT,
    PRIMARY KEY (k1, k2)
);

-- no primary key, should be ignored
CREATE TABLE t3 (
    v1 INT
);
