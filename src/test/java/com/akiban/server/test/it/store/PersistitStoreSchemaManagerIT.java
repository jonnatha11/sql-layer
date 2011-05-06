/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.test.it.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;

import com.akiban.ais.ddl.SchemaDef;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableName;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.SchemaManager;
import com.akiban.server.store.TableDefinition;
import com.akiban.server.test.it.ITBase;
import com.akiban.util.Command;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.UserTable;
import com.akiban.message.ErrorCode;
import com.akiban.server.AkServer;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.service.config.Property;
import com.akiban.util.MySqlStatementSplitter;

public final class PersistitStoreSchemaManagerIT extends ITBase {
    private final static String SCHEMA = "my_schema";
    private final static String VOL2_PREFIX = "foo_schema";
    private final static String VOL3_PREFIX = "bar_schema";

    private final static String T1_NAME = "t1";
    private final static String T1_DDL = "create table t1(id int, PRIMARY KEY(id)) engine=akibandb;";
    private final static String T2_NAME = "t2";
    private final static String T2_DDL = "create table t2(id int, PRIMARY KEY(id)) engine=akibandb;";
    private final static String T3_CHILD_T1_NAME = "t3";
    private final static String T3_CHILD_T1_DDL = "create table t3(id int, t1id int, PRIMARY KEY(id), "+
                                                  "constraint __akiban foreign key(t1id) references t1(id)) engine=akibandb;";

    private SchemaManager schemaManager;

    private void createTableDef(String schema, String ddl) throws Exception {
        schemaManager.createTableDefinition(session(), schema, ddl, false);
    }

    private void deleteTableDef(String schema, String table) throws Exception {
        schemaManager.deleteTableDefinition(session(), schema, table);
    }

    private TableDefinition getTableDef(String schema, String table) throws Exception {
        return schemaManager.getTableDefinition(session(), schema, table);
    }
    
    @Override
    protected Collection<Property> startupConfigProperties() {
        // Set up multi-volume treespace policy so we can be sure schema is properly distributed.
        final Collection<Property> properties = new ArrayList<Property>();
        properties.add(new Property("akserver.treespace.a",
                                    VOL2_PREFIX + "*:${datapath}/${schema}.v0,create,pageSize:${buffersize},"
                                    + "initialSize:10K,extensionSize:1K,maximumSize:10G"));
        properties.add(new Property("akserver.treespace.b",
                                    VOL3_PREFIX + "*:${datapath}/${schema}.v0,create,pageSize:${buffersize},"
                                    + "initialSize:10K,extensionSize:1K,maximumSize:10G"));
        return properties;
    }

    @Before
    public void setUp() throws Exception {
        schemaManager = serviceManager().getSchemaManager();
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void getUnknownTableDefinition() throws Exception {
        final TableDefinition def = getTableDef("fooschema", "bartable1");
        assertNull("TableDefinition is null", def);
    }

    // Also tests createDef(), but assertTablesInSchema() uses getDef() so try and test first
    @Test
    public void getTableDefinition() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        final TableDefinition def = getTableDef(SCHEMA, T1_NAME);
        assertNotNull("Definition exists", def);
    }

    @Test
    public void getTableDefinitionsUnknownSchema() throws Exception {
        final SortedMap<String, TableDefinition> defs = schemaManager.getTableDefinitions(session(), "fooschema");
        assertEquals("no defs", 0, defs.size());
    }

    @Test
    public void getTableDefinitionsOneSchema() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        createTableDef(SCHEMA, T2_DDL);
        createTableDef(SCHEMA + "_bob", T1_DDL);
        final SortedMap<String, TableDefinition> defs = schemaManager.getTableDefinitions(session(), SCHEMA);
        assertTrue("contains t1", defs.containsKey(T1_NAME));
        assertTrue("contains t2", defs.containsKey(T2_NAME));
        assertEquals("no defs", 2, defs.size());
    }

    @Test
    public void createOneDefinition() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);
    }

    @Test
    public void deleteOneDefinition() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);
        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteUnknownDefinition() throws Exception {
        deleteTableDef("schema1", "table1");
        assertTablesInSchema(SCHEMA);
        deleteTableDef("schema1", "table1");
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteDefinitionTwice() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);
        
        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA);
        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteTwoDefinitions() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);

        createTableDef(SCHEMA, T2_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME, T2_NAME);

        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA, T2_NAME);

        deleteTableDef(SCHEMA, T2_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteChildDefinition() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);

        createTableDef(SCHEMA, T3_CHILD_T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME, T3_CHILD_T1_NAME);

        // Deleting child should not delete parent
        deleteTableDef(SCHEMA, T3_CHILD_T1_NAME);
        assertTablesInSchema(SCHEMA, T1_NAME);

        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteParentDefinitionFirst() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME);

        createTableDef(SCHEMA, T3_CHILD_T1_DDL);
        assertTablesInSchema(SCHEMA, T1_NAME, T3_CHILD_T1_NAME);

        final AkibanInformationSchema ais = ddl().getAIS(session());
        final UserTable t1 = ais.getUserTable(SCHEMA, T1_NAME);
        assertNotNull("t1 exists", t1);
        final UserTable t3 = ais.getUserTable(SCHEMA, T3_CHILD_T1_NAME);
        assertNotNull("t3 exists", t3);

        // Double check grouping we are expecting
        assertNotNull("t3 has parent", t3.getParentJoin());
        assertSame("t1 is t3 parent", t1, t3.getParentJoin().getParent());
        assertNotNull("t1 has children", t1.getCandidateChildJoins());
        assertEquals("t1 has 1 child", 1, t1.getCandidateChildJoins().size());
        assertSame("t3 is t1 child", t3, t1.getCandidateChildJoins().get(0).getChild());
        
        try {
            deleteTableDef(SCHEMA, T1_NAME);
            Assert.fail("Exception expected!");
        } catch(InvalidOperationException e) {
            assertEquals("error code", ErrorCode.UNSUPPORTED_MODIFICATION, e.getCode());
        }

        assertTablesInSchema(SCHEMA, T1_NAME, T3_CHILD_T1_NAME);
        deleteTableDef(SCHEMA, T3_CHILD_T1_NAME);
        assertTablesInSchema(SCHEMA, T1_NAME);
        deleteTableDef(SCHEMA, T1_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void createTwoDefinitionsTwoVolumes() throws Exception {
        final String SCHEMA_VOL2_A = VOL2_PREFIX + "_a";
        final String SCHEMA_VOL2_B = VOL2_PREFIX + "_b";

        assertTablesInSchema(SCHEMA_VOL2_A);
        assertTablesInSchema(SCHEMA_VOL2_B);
        assertTablesInSchema(SCHEMA);

        createTableDef(SCHEMA_VOL2_A, T1_DDL);
        assertTablesInSchema(SCHEMA_VOL2_A, T1_NAME);
        assertTablesInSchema(SCHEMA);

        createTableDef(SCHEMA_VOL2_B, T2_DDL);
        assertTablesInSchema(SCHEMA_VOL2_B, T2_NAME);
        assertTablesInSchema(SCHEMA_VOL2_A, T1_NAME);
        assertTablesInSchema(SCHEMA);
    }

    @Test
    public void deleteTwoDefinitionsTwoVolumes() throws Exception {
        final String SCHEMA_VOL2_A = VOL2_PREFIX + "_a";
        final String SCHEMA_VOL2_B = VOL2_PREFIX + "_b";

        createTableDef(SCHEMA_VOL2_A, T1_DDL);
        createTableDef(SCHEMA_VOL2_B, T2_DDL);
        assertTablesInSchema(SCHEMA_VOL2_A, T1_NAME);
        assertTablesInSchema(SCHEMA_VOL2_B, T2_NAME);

        deleteTableDef(SCHEMA_VOL2_A, T1_NAME);
        assertTablesInSchema(SCHEMA_VOL2_A);
        assertTablesInSchema(SCHEMA_VOL2_B, T2_NAME);

        deleteTableDef(SCHEMA_VOL2_B, T2_NAME);
        assertTablesInSchema(SCHEMA_VOL2_A);
        assertTablesInSchema(SCHEMA_VOL2_B);
    }

    @Test
    public void updateTimestampChangesWithCreate() throws Exception {
        final long first = schemaManager.getUpdateTimestamp();
        createTableDef(SCHEMA, T1_DDL);
        final long second = schemaManager.getUpdateTimestamp();
        assertTrue("timestamp changed", first != second);
    }

    @Test
    public void updateTimestampChangesWithDelete() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        final long first = schemaManager.getUpdateTimestamp();
        deleteTableDef(SCHEMA, T1_NAME);
        final long second = schemaManager.getUpdateTimestamp();
        assertTrue("timestamp changed", first != second);
    }

    @Test
    public void schemaGenChangesWithCreate() throws Exception {
        final int first = schemaManager.getSchemaGeneration();
        createTableDef(SCHEMA, T1_DDL);
        final int second = schemaManager.getSchemaGeneration();
        assertTrue("timestamp changed", first != second);
    }

    @Test
    public void schemaGenChangesWithDelete() throws Exception {
        createTableDef(SCHEMA, T1_DDL);
        final int first = schemaManager.getSchemaGeneration();
        deleteTableDef(SCHEMA, T1_NAME);
        final int second = schemaManager.getSchemaGeneration();
        assertTrue("timestamp changed", first != second);
    }

    @Test
    public void forceNewTimestampChangesTimestamp() throws Exception {
        final long first = schemaManager.getUpdateTimestamp();
        schemaManager.forceNewTimestamp();
        final long second = schemaManager.getUpdateTimestamp();
        assertTrue("timestamp changed", first != second);
    }

    @Test
    public void forceNewTimestampChangesSchemaGen() throws Exception {
        final int first = schemaManager.getSchemaGeneration();
        schemaManager.forceNewTimestamp();
        final int second = schemaManager.getSchemaGeneration();
        assertTrue("timestamp changed", first != second);
    }

    /**
     * Assert that the given tables in the given schema has the, and only the, given tables. Also
     * confirm each table exists in the AIS and has a definition.
     * @param schema Name of schema to check.
     * @param tableNames List of table names to check.
     * @throws Exception For any internal error.
     */
    private void assertTablesInSchema(String schema, String... tableNames) throws Exception {
        final SortedSet<String> expected = new TreeSet<String>();
        final AkibanInformationSchema ais = ddl().getAIS(session());
        final Session session = session();
        for (String name : tableNames) {
            final Table table = ais.getTable(schema, name);
            assertNotNull(schema + "." + name + " in AIS", table);
            final TableDefinition def = schemaManager.getTableDefinition(session, schema, name);
            assertNotNull(schema + "." + name  + " has definition", def);
            expected.add(name);
        }
        final SortedSet<String> actual = new TreeSet<String>();
        for (TableDefinition def : schemaManager.getTableDefinitions(session, schema).values()) {
            final Table table = ais.getTable(schema, def.getTableName());
            assertNotNull(def + " in AIS", table);
            actual.add(def.getTableName());
        }
        assertEquals("tables in: " + schema, expected, actual);
    }
}
