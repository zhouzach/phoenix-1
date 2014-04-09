/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.phoenix.pig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.phoenix.jdbc.PhoenixDriver;
import org.apache.phoenix.pig.PhoenixHBaseStorage;
import org.apache.phoenix.util.ConfigUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.backend.executionengine.ExecJob.JOB_STATUS;
import org.apache.pig.builtin.mock.Storage;
import org.apache.pig.builtin.mock.Storage.Data;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Lists;

/**
 * Tests for {@link PhoenixHBaseStorage}
 * 
 */
public class PhoenixHBaseStorageTest {
    private static TupleFactory tupleFactory;
    private static HBaseTestingUtility hbaseTestUtil;
    private static String zkQuorum;
    private static Connection conn;
    private static PigServer pigServer;

    @BeforeClass
    public static void setUp() throws Exception {
        hbaseTestUtil = new HBaseTestingUtility();
        Configuration conf = hbaseTestUtil.getConfiguration();
        ConfigUtil.setReplicationConfigIfAbsent(conf);
        hbaseTestUtil.startMiniCluster();

        Class.forName(PhoenixDriver.class.getName());
        zkQuorum = "localhost:" + hbaseTestUtil.getZkCluster().getClientPort();
        conn = DriverManager.getConnection(PhoenixRuntime.JDBC_PROTOCOL
                + PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR + zkQuorum);

        // Pig variables
        pigServer = new PigServer(ExecType.LOCAL);
        tupleFactory = TupleFactory.getInstance();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        conn.close();
        PhoenixDriver.INSTANCE.close();
        hbaseTestUtil.shutdownMiniCluster();
        pigServer.shutdown();
    }

    /**
     * Basic test - writes data to a Phoenix table and compares the data written
     * to expected
     * 
     * @throws Exception
     */
    @Test
    public void testStorer() throws Exception {
        final String tableName = "TABLE1";
        final Statement stmt = conn.createStatement();

        stmt.execute("CREATE TABLE " + tableName
                + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR)");

        final Data data = Storage.resetData(pigServer);
        final Collection<Tuple> list = Lists.newArrayList();

        // Create input dataset
        int rows = 100;
        for (int i = 0; i < rows; i++) {
            Tuple t = tupleFactory.newTuple();
            t.append(i);
            t.append("a" + i);
            list.add(t);
        }
        data.set("in", "id:int, name:chararray", list);

        pigServer.setBatchOn();
        pigServer.registerQuery("A = LOAD 'in' USING mock.Storage();");

        pigServer.registerQuery("Store A into 'hbase://" + tableName
                + "' using " + PhoenixHBaseStorage.class.getName() + "('"
                + zkQuorum + "', '-batchSize 1000');");

        // Now run the Pig script
        if (pigServer.executeBatch().get(0).getStatus() != JOB_STATUS.COMPLETED) {
            throw new RuntimeException("Job failed", pigServer.executeBatch()
                    .get(0).getException());
        }

        // Compare data in Phoenix table to the expected
        final ResultSet rs = stmt
                .executeQuery("SELECT id, name FROM table1 ORDER BY id");

        for (int i = 0; i < rows; i++) {
            assertTrue(rs.next());
            assertEquals(i, rs.getInt(1));
            assertEquals("a" + i, rs.getString(2));
        }
    }

}
