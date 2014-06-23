/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.aisddl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.foundationdb.sql.parser.IndexDefinitionNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.foundationdb.ais.model.AISBuilder;
import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.DefaultIndexNameGenerator;
import com.foundationdb.ais.model.ForeignKey;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.IndexNameGenerator;
import com.foundationdb.ais.model.PrimaryKey;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.protobuf.FDBProtobuf.TupleUsage;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.server.api.DDLFunctions;
import com.foundationdb.server.error.*;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.store.format.tuple.TupleStorageDescription;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.server.types.common.types.TypesTranslator;
import com.foundationdb.sql.optimizer.FunctionsTypeComputer;
import com.foundationdb.sql.parser.ColumnDefinitionNode;
import com.foundationdb.sql.parser.ConstantNode;
import com.foundationdb.sql.parser.ConstraintDefinitionNode;
import com.foundationdb.sql.parser.CreateTableNode;
import com.foundationdb.sql.parser.CurrentDatetimeOperatorNode;
import com.foundationdb.sql.parser.DropGroupNode;
import com.foundationdb.sql.parser.DropTableNode;
import com.foundationdb.sql.parser.ExistenceCheck;
import com.foundationdb.sql.parser.FKConstraintDefinitionNode;
import com.foundationdb.sql.parser.IndexColumnList;
import com.foundationdb.sql.parser.IndexDefinition;
import com.foundationdb.sql.parser.RenameNode;
import com.foundationdb.sql.parser.ResultColumn;
import com.foundationdb.sql.parser.ResultColumnList;
import com.foundationdb.sql.parser.SpecialFunctionNode;
import com.foundationdb.sql.parser.StatementType;
import com.foundationdb.sql.parser.StorageFormatNode;
import com.foundationdb.sql.parser.TableElementNode;
import com.foundationdb.sql.parser.ValueNode;
import com.foundationdb.sql.parser.JavaToSQLValueNode;
import com.foundationdb.sql.parser.MethodCallNode;
import com.foundationdb.sql.types.DataTypeDescriptor;
import com.foundationdb.sql.types.TypeId;

import static com.foundationdb.sql.aisddl.DDLHelper.convertName;

/** DDL operations on Tables */
public class TableDDL
{
    //private final static Logger logger = LoggerFactory.getLogger(TableDDL.class);
    private TableDDL() {
    }

    public static void dropTable (DDLFunctions ddlFunctions,
                                  Session session, 
                                  String defaultSchemaName,
                                  DropTableNode dropTable,
                                  QueryContext context) {
        TableName tableName = convertName(defaultSchemaName, dropTable.getObjectName());
        ExistenceCheck existenceCheck = dropTable.getExistenceCheck();

        AkibanInformationSchema ais = ddlFunctions.getAIS(session);
        
        Table table = ais.getTable(tableName);
        if (table == null) {
            if (existenceCheck == ExistenceCheck.IF_EXISTS)
            {
                if (context != null)
                    context.warnClient(new NoSuchTableException (tableName.getSchemaName(), tableName.getTableName()));
                return;
            }
            throw new NoSuchTableException (tableName.getSchemaName(), tableName.getTableName());
        }
        ViewDDL.checkDropTable(ddlFunctions, session, tableName);
        checkForeignKeyDropTable(table);
        ddlFunctions.dropTable(session, tableName);
    }

    public static void dropGroup (DDLFunctions ddlFunctions,
                                    Session session,
                                    String defaultSchemaName,
                                    DropGroupNode dropGroup,
                                    QueryContext context)
    {
        TableName tableName = convertName(defaultSchemaName, dropGroup.getObjectName());
        ExistenceCheck existenceCheck = dropGroup.getExistenceCheck();
        AkibanInformationSchema ais = ddlFunctions.getAIS(session);
        
        if (ais.getTable(tableName) == null) {
            if (existenceCheck == ExistenceCheck.IF_EXISTS) {
                if (context != null) {
                    context.warnClient(new NoSuchTableException (tableName));
                }
                return;
            }
            throw new NoSuchTableException (tableName);
        } 
        if (!ais.getTable(tableName).isRoot()) {
            throw new DropGroupNotRootException (tableName);
        }
        
        final Group root = ais.getTable(tableName).getGroup();
        for (Table table : ais.getTables().values()) {
            if (table.getGroup() == root) {
                ViewDDL.checkDropTable(ddlFunctions, session, table.getName());
                checkForeignKeyDropTable(table);
            }
        }
        ddlFunctions.dropGroup(session, root.getName());
    }
    
    private static void checkForeignKeyDropTable(Table table) {
        for (ForeignKey foreignKey : table.getReferencedForeignKeys()) {
            if (table != foreignKey.getReferencingTable()) {
                throw new ForeignKeyPreventsDropTableException(table.getName(), foreignKey.getConstraintName(), foreignKey.getReferencingTable().getName());
            }
        }
    }

    public static void renameTable (DDLFunctions ddlFunctions,
                                    Session session,
                                    String defaultSchemaName,
                                    RenameNode renameTable) {
        TableName oldName = convertName(defaultSchemaName, renameTable.getObjectName());
        TableName newName = convertName(defaultSchemaName, renameTable.getNewTableName());
        ddlFunctions.renameTable(session, oldName, newName);
    }

    public static void createTable(DDLFunctions ddlFunctions,
                                   Session session,
                                   String defaultSchemaName,
                                   CreateTableNode createTable,
                                   QueryContext context) {
        if (createTable.getQueryExpression() != null)
            throw new UnsupportedCreateSelectException();

        com.foundationdb.sql.parser.TableName parserName = createTable.getObjectName();
        String schemaName = parserName.hasSchema() ? parserName.getSchemaName() : defaultSchemaName;
        String tableName = parserName.getTableName();
        ExistenceCheck condition = createTable.getExistenceCheck();

        AkibanInformationSchema ais = ddlFunctions.getAIS(session);

        if (ais.getTable(schemaName, tableName) != null)
            switch(condition)
            {
                case IF_NOT_EXISTS:
                    // table already exists. does nothing
                    if (context != null)
                        context.warnClient(new DuplicateTableNameException(schemaName, tableName));
                    return;
                case NO_CONDITION:
                    throw new DuplicateTableNameException(schemaName, tableName);
                default:
                    throw new IllegalStateException("Unexpected condition: " + condition);
            }

        TypesTranslator typesTranslator = ddlFunctions.getTypesTranslator();
        AISBuilder builder = new AISBuilder();
        builder.table(schemaName, tableName);
        Table table = builder.akibanInformationSchema().getTable(schemaName, tableName);
        IndexNameGenerator namer = DefaultIndexNameGenerator.forTable(table);

        int colpos = 0;
        // first loop through table elements, add the columns
        for (TableElementNode tableElement : createTable.getTableElementList()) {
            if (tableElement instanceof ColumnDefinitionNode) {
                addColumn (builder, typesTranslator,
                           (ColumnDefinitionNode)tableElement, schemaName, tableName, colpos++);
            }
        }
        // second pass get the constraints (primary, FKs, and other keys)
        // This needs to be done in two passes as the parser may put the 
        // constraint before the column definition. For example:
        // CREATE TABLE t1 (c1 INT PRIMARY KEY) produces such a result. 
        // The Builder complains if you try to do such a thing. 
        for (TableElementNode tableElement : createTable.getTableElementList()) {
            if (tableElement instanceof FKConstraintDefinitionNode) {
                FKConstraintDefinitionNode fkdn = (FKConstraintDefinitionNode)tableElement;
                if (fkdn.isGrouping()) {
                    addParentTable(builder, ddlFunctions.getAIS(session), fkdn, defaultSchemaName, schemaName, tableName);
                    addJoin (builder, fkdn, defaultSchemaName, schemaName, tableName);
                } else {
                    addForeignKey(builder, ddlFunctions.getAIS(session), fkdn, defaultSchemaName, schemaName, tableName);
                }
            }
            else if (tableElement instanceof ConstraintDefinitionNode) {
                addIndex (namer, builder, (ConstraintDefinitionNode)tableElement, schemaName, tableName, context);
            } else if (tableElement instanceof IndexDefinitionNode) {
                addIndex (namer, builder, (IndexDefinitionNode)tableElement, schemaName, tableName, context);
            } else if (!(tableElement instanceof ColumnDefinitionNode)) {
                throw new UnsupportedSQLException("Unexpected TableElement", tableElement);
            }
        }
        builder.basicSchemaIsComplete();
        builder.groupingIsComplete();
        
        if (createTable.getStorageFormat() != null) {
            if (!table.isRoot()) {
                throw new SetStorageNotRootException(tableName, schemaName);
            }
            if (table.getGroup() == null) {
                builder.createGroup(tableName, schemaName);
                builder.addTableToGroup(tableName, schemaName, tableName);
            }
            setStorage(ddlFunctions, table.getGroup(), createTable.getStorageFormat());
        }
        else {
            if (table.isRoot()) {
                if (table.getGroup() == null) {
                    builder.createGroup(tableName, schemaName);
                    builder.addTableToGroup(tableName, schemaName, tableName);
                }
                table.getGroup().setStorageDescription(ddlFunctions.getStorageFormatRegistry().
                        getDefaultStorageDescription(table.getGroup()));
                if (table.getGroup().getStorageDescription() instanceof TupleStorageDescription) {
                    TupleStorageDescription tsd = (TupleStorageDescription) table.getGroup().getStorageDescription();
                    tsd.setUsage(TupleUsage.KEY_ONLY);
                }
            }
        }

        ddlFunctions.createTable(session, table);
    }
    
    public static void setStorage(DDLFunctions ddlFunctions,
                                  HasStorage object, 
                                  StorageFormatNode storage) {
        object.setStorageDescription(ddlFunctions.getStorageFormatRegistry().parseSQL(storage, object));
    }

    static void addColumn (final AISBuilder builder, final TypesTranslator typesTranslator, final ColumnDefinitionNode cdn,
                           final String schemaName, final String tableName, int colpos) {

        String typeName = cdn.getType().getTypeName();
        // Special handling for the "[BIG]SERIAL" column type -> which is transformed to
        // [BIG]INT NOT NULL GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1)
        boolean isSerial = "serial".equalsIgnoreCase(typeName);
        boolean isBigSerial = "bigserial".equalsIgnoreCase(typeName);
        if (isSerial || isBigSerial) {
            // [BIG]INT NOT NULL
            DataTypeDescriptor typeDesc = new DataTypeDescriptor(isBigSerial ? TypeId.BIGINT_ID : TypeId.INTEGER_ID, false);
            addColumn (builder, typesTranslator,
                       schemaName, tableName, cdn.getColumnName(), colpos,
                       typeDesc, null, null);
            // GENERATED BY DEFAULT AS IDENTITY
            setAutoIncrement (builder, schemaName, tableName, cdn.getColumnName(), true, 1, 1);
        } else {
            String[] defaultValueFunction = getColumnDefault(cdn, schemaName, tableName);
            addColumn(builder, typesTranslator,
                      schemaName, tableName, cdn.getColumnName(), colpos,
                      cdn.getType(), defaultValueFunction[0], defaultValueFunction[1]);
            if (cdn.isAutoincrementColumn()) {
                setAutoIncrement(builder, schemaName, tableName, cdn);
            }
        }
    }

    public static void setAutoIncrement(AISBuilder builder, String schema, String table, ColumnDefinitionNode cdn) {
        // if the cdn has a default node-> GENERATE BY DEFAULT
        // if no default node -> GENERATE ALWAYS
        Boolean defaultIdentity = cdn.getDefaultNode() != null;
        setAutoIncrement(builder, schema, table, cdn.getColumnName(),
                         defaultIdentity, cdn.getAutoincrementStart(), cdn.getAutoincrementIncrement());
    }

    public static void setAutoIncrement(AISBuilder builder, String schemaName, String tableName, String columnName,
                                        boolean defaultIdentity, long start, long increment) {
        // make the column an identity column 
        builder.columnAsIdentity(schemaName, tableName, columnName, start, increment, defaultIdentity);
    }
    
    static String[] getColumnDefault(ColumnDefinitionNode cdn, 
                                     String schemaName, String tableName) {
        String defaultValue = null, defaultFunction = null;
        if (cdn.getDefaultNode() != null) {
            ValueNode valueNode = cdn.getDefaultNode().getDefaultTree();
            if (valueNode == null) {
            }
            else if (valueNode instanceof ConstantNode) {
                defaultValue = ((ConstantNode)valueNode).getValue().toString();
            }
            else if (valueNode instanceof SpecialFunctionNode) {
                defaultFunction = FunctionsTypeComputer.specialFunctionName((SpecialFunctionNode)valueNode);
            }
            else if (valueNode instanceof CurrentDatetimeOperatorNode) {
                defaultFunction = FunctionsTypeComputer.currentDatetimeFunctionName((CurrentDatetimeOperatorNode)valueNode);
            }
            else if ((valueNode instanceof JavaToSQLValueNode) && 
                    (((JavaToSQLValueNode) valueNode).getJavaValueNode() instanceof MethodCallNode) &&
                    (((MethodCallNode) ((JavaToSQLValueNode) valueNode).getJavaValueNode()).getMethodParameters().length == 0)) {
                // if default is a method with no arguments:
                defaultFunction = ((MethodCallNode) ((JavaToSQLValueNode) valueNode).getJavaValueNode()).getMethodName();
            }
            else {
                throw new BadColumnDefaultException(schemaName, tableName, 
                                                    cdn.getColumnName(), 
                                                    cdn.getDefaultNode().getDefaultText());
            }
        }
        return new String[] { defaultValue, defaultFunction };
    }

    static void addColumn(final AISBuilder builder, final TypesTranslator typesTranslator,
                          final String schemaName, final String tableName, final String columnName,
                          int colpos, DataTypeDescriptor sqlType,
                          final String defaultValue, final String defaultFunction) {
        TInstance type = typesTranslator.typeForSQLType(sqlType,
                schemaName, tableName, columnName);
        builder.column(schemaName, tableName, columnName, 
                       colpos, type, false, defaultValue, defaultFunction);
    }

    private static final Logger logger = LoggerFactory.getLogger(TableDDL.class);


    public static String addIndex(IndexNameGenerator namer, AISBuilder builder, ConstraintDefinitionNode cdn,
                                  String schemaName, String tableName, QueryContext context)  {
        // We don't (yet) have a constraint representation so override any provided
        Table table = builder.akibanInformationSchema().getTable(schemaName, tableName);
        final String constraint;
        String indexName = cdn.getName();
        int colPos = 0;

        if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.CHECK) {
            throw new UnsupportedCheckConstraintException ();
        }
        else if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.PRIMARY_KEY) {
            indexName = constraint = Index.PRIMARY_KEY_CONSTRAINT;
        }
        else if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.UNIQUE) {
            constraint = Index.UNIQUE_KEY_CONSTRAINT;
        } else {
            throw new UnsupportedCheckConstraintException();
        }

        if(indexName == null) {
            indexName = namer.generateIndexName(null, cdn.getColumnList().get(0).getName(), constraint);
        }
        
        builder.index(schemaName, tableName, indexName, true, constraint);
        
        for (ResultColumn col : cdn.getColumnList()) {
            if(table.getColumn(col.getName()) == null) {
                throw new NoSuchColumnException(col.getName());
            }
            builder.indexColumn(schemaName, tableName, indexName, col.getName(), colPos++, true, null);
        }
        return indexName;
    }

    public static String addIndex(IndexNameGenerator namer,
                                  AISBuilder builder,
                                  IndexDefinitionNode idn,
                                  String schemaName,
                                  String tableName,
                                  QueryContext context) {
        String indexName = idn.getName();
        Table table = builder.akibanInformationSchema().getTable(schemaName, tableName);
        return generateTableIndex(namer, builder, idn, indexName, table, context);
    }

    public static TableName getReferencedName(String schemaName, FKConstraintDefinitionNode fkdn) {
        return convertName(schemaName, fkdn.getRefTableName());
    }

    public static void addJoin(final AISBuilder builder, final FKConstraintDefinitionNode fkdn,
                               final String defaultSchemaName, final String schemaName, final String tableName)  {
        TableName parentName = getReferencedName(defaultSchemaName, fkdn);
        String joinName = String.format("%s/%s/%s/%s",
                                        parentName.getSchemaName(),
                                        parentName.getTableName(),
                                        schemaName, tableName);

        AkibanInformationSchema ais = builder.akibanInformationSchema();
        // Check parent table exists
        Table parentTable = ais.getTable(parentName);
        if (parentTable == null) {
            throw new JoinToUnknownTableException(new TableName(schemaName, tableName), parentName);
        }
        // Check child table exists
        Table childTable = ais.getTable(schemaName, tableName);
        if (childTable == null) {
            throw new NoSuchTableException(schemaName, tableName);
        }
        // Check that we aren't joining to ourselves
        if (parentTable == childTable) {
            throw new JoinToSelfException(schemaName, tableName);
        }
        // Check that fk list and pk list are the same size
        String[] fkColumns = columnNamesFromListOrPK(fkdn.getColumnList(), null); // No defaults for child table
        String[] pkColumns = columnNamesFromListOrPK(fkdn.getRefResultColumnList(), parentTable.getPrimaryKey());

        int actualPkColCount = parentTable.getPrimaryKeyIncludingInternal().getColumns().size();
        if ((fkColumns.length != actualPkColCount) || (pkColumns.length != actualPkColCount)) {
            throw new JoinColumnMismatchException(fkdn.getColumnList().size(),
                                                  new TableName(schemaName, tableName),
                                                  parentName,
                                                  parentTable.getPrimaryKeyIncludingInternal().getColumns().size());
        }

        int colPos = 0;
        while((colPos < fkColumns.length) && (colPos < pkColumns.length)) {
            String fkColumn = fkColumns[colPos];
            String pkColumn = pkColumns[colPos];
            if (childTable.getColumn(fkColumn) == null) {
                throw new NoSuchColumnException(String.format("%s.%s.%s", schemaName, tableName, fkColumn));
            }
            if (parentTable.getColumn(pkColumn) == null) {
                throw new JoinToWrongColumnsException(new TableName(schemaName, tableName),
                                                      fkColumn,
                                                      parentName,
                                                      pkColumn);
            }
            ++colPos;
        }

        builder.joinTables(joinName, parentName.getSchemaName(), parentName.getTableName(), schemaName, tableName);

        colPos = 0;
        while(colPos < fkColumns.length) {
            builder.joinColumns(joinName,
                                parentName.getSchemaName(), parentName.getTableName(), pkColumns[colPos],
                                schemaName, tableName, fkColumns[colPos]);
            ++colPos;
        }
        builder.addJoinToGroup(parentTable.getGroup().getName(), joinName, 0);
    }
    
    /**
     * Add a minimal parent table (PK) with group to the builder based upon the AIS.
     */
    public static void addParentTable(AISBuilder builder,
                                      AkibanInformationSchema ais,
                                      FKConstraintDefinitionNode fkdn,
                                      String defaultSchemaName,
                                      String childSchemaName,
                                      String childTableName) {

        TableName parentName = getReferencedName(defaultSchemaName, fkdn);
        // Check that we aren't joining to ourselves
        if (parentName.equals(childSchemaName, childTableName)) {
            throw new JoinToSelfException(childSchemaName, childTableName);
        }
        // Check parent table exists
        Table parentTable = ais.getTable(parentName);
        if (parentTable == null) {
            throw new JoinToUnknownTableException(new TableName(childSchemaName, childTableName), parentName);
        }

        builder.table(parentName.getSchemaName(), parentName.getTableName());
        
        builder.index(parentName.getSchemaName(), parentName.getTableName(), Index.PRIMARY_KEY_CONSTRAINT, true,
                      Index.PRIMARY_KEY_CONSTRAINT);
        int colpos = 0;
        for (Column column : parentTable.getPrimaryKeyIncludingInternal().getColumns()) {
            builder.column(parentName.getSchemaName(), parentName.getTableName(),
                    column.getName(),
                    colpos,
                    column.getType(),
                    false, //column.getInitialAutoIncrementValue() != 0,
                    column.getCharsetName(),
                    column.getCollationName());
            builder.indexColumn(parentName.getSchemaName(), parentName.getTableName(), Index.PRIMARY_KEY_CONSTRAINT,
                    column.getName(), colpos++, true, null);
        }
        final TableName groupName;
        if(parentTable.getGroup() == null) {
            groupName = parentName;
        } else {
            groupName = parentTable.getGroup().getName();
        }
        builder.createGroup(groupName.getTableName(), groupName.getSchemaName());
        builder.addTableToGroup(groupName, parentName.getSchemaName(), parentName.getTableName());
    }


    private static String[] columnNamesFromListOrPK(ResultColumnList list, PrimaryKey pk) {
        String[] names = (list == null) ? null: list.getColumnNames();
        if(((names == null) || (names.length == 0)) && (pk != null)) {
            Index index = pk.getIndex();
            names = new String[index.getKeyColumns().size()];
            int i = 0;
            for(IndexColumn iCol : index.getKeyColumns()) {
                names[i++] = iCol.getColumn().getName();
            }
        }
        if(names == null) {
            names = new String[0];
        }
        return names;
    }
    
    private static String generateTableIndex(IndexNameGenerator namer,
            AISBuilder builder,
            IndexDefinition id,
            String indexName,
            Table table,
            QueryContext context) {
        IndexColumnList columnList = id.getIndexColumnList();
        Index tableIndex;
        if(indexName == null) {
            indexName = namer.generateIndexName(null, columnList.get(0).getColumnName(), Index.KEY_CONSTRAINT);
        }

        if (columnList.functionType() == IndexColumnList.FunctionType.FULL_TEXT) {
            logger.debug ("Building Full text index on table {}", table.getName()) ;
            tableIndex = IndexDDL.buildFullTextIndex (builder, table.getName(), indexName, id);
        } else if (IndexDDL.checkIndexType (id, table.getName()) == Index.IndexType.TABLE) {
            logger.debug ("Building Table index on table {}", table.getName()) ;
            tableIndex = IndexDDL.buildTableIndex (builder, table.getName(), indexName, id);
        } else {
            logger.debug ("Building Group index on table {}", table.getName());
            tableIndex = IndexDDL.buildGroupIndex (builder, table.getName(), indexName, id);
        }

        boolean indexIsSpatial = columnList.functionType() == IndexColumnList.FunctionType.Z_ORDER_LAT_LON;
        // Can't check isSpatialCompatible before the index columns have been added.
        if (indexIsSpatial && !Index.isSpatialCompatible(tableIndex)) {
            throw new BadSpatialIndexException(tableIndex.getIndexName().getTableName(), null);
        }
        return tableIndex.getIndexName().getName();
    }

    protected static void addForeignKey(AISBuilder builder,
                                        AkibanInformationSchema sourceAIS,
                                        FKConstraintDefinitionNode fkdn,
                                        String defaultSchemaName,
                                        String referencingSchemaName,
                                        String referencingTableName) {
        AkibanInformationSchema targetAIS = builder.akibanInformationSchema();
        Table referencingTable = targetAIS.getTable(referencingSchemaName, referencingTableName);
        TableName referencedName = getReferencedName(defaultSchemaName, fkdn);
        Table referencedTable = sourceAIS.getTable(referencedName);
        if (referencedTable == null) {
            if (referencedName.equals(referencingTable.getName())) {
                referencedTable = referencingTable; // Circular reference to self.
            }
            else {
                throw new JoinToUnknownTableException(new TableName(referencingSchemaName, referencingTableName), referencedName);
            }
        }
        if (fkdn.getMatchType() != FKConstraintDefinitionNode.MatchType.SIMPLE) {
            throw new UnsupportedSQLException("MATCH " + fkdn.getMatchType(), fkdn);
        }
        String constraintName = fkdn.getName();
        if (constraintName == null) {
            constraintName = "__fk_" + (referencingTable.getForeignKeys().size() + 1);
        }
        String[] referencingColumnNames = columnNamesFromListOrPK(fkdn.getColumnList(), 
                                                                  null);
        String[] referencedColumnNames = columnNamesFromListOrPK(fkdn.getRefResultColumnList(), 
                                                                 referencedTable.getPrimaryKey());
        if (referencingColumnNames.length != referencedColumnNames.length) {
            throw new JoinColumnMismatchException(referencingColumnNames.length,
                                                  new TableName(referencingSchemaName, referencingTableName),
                                                  referencedName,
                                                  referencedColumnNames.length);
        }
        List<Column> referencedColumns = new ArrayList<>(referencedColumnNames.length);
        for (int i = 0; i < referencingColumnNames.length; i++) {
            if (referencingTable.getColumn(referencingColumnNames[i]) == null) {
                throw new NoSuchColumnException(referencingColumnNames[i]);
            }
            Column referencedColumn = referencedTable.getColumn(referencedColumnNames[i]);
            if (referencedColumn == null) {
                throw new NoSuchColumnException(referencedColumnNames[i]);
            }
            referencedColumns.add(referencedColumn);
        }
        // Make sure that there is enough of a referenced table for
        // the builder to be able to make objects that serialize okay.
        Table shadowTable = targetAIS.getTable(referencedName);
        if (shadowTable == null) {
            builder.table(referencedName.getSchemaName(), referencedName.getTableName());
            shadowTable = targetAIS.getTable(referencedName);
        }
        // Pick an index.
        TableIndex referencedIndex = ForeignKey.findReferencedIndex(referencedTable,
                                                                    referencedColumns);
        if (referencedIndex == null) {
            throw new ForeignKeyIndexRequiredException(constraintName, referencedName,
                                                       Arrays.toString(referencedColumnNames));
        }
        // Make sure that there is a shadow of referenced columns, too.
        for (Column column : referencedColumns) {
            if (shadowTable.getColumn(column.getName()) == null) {
                builder.column(referencedName.getSchemaName(), referencedName.getTableName(),
                               column.getName(),
                               column.getPosition(),
                               column.getType(),
                               false,
                               column.getCharsetName(),
                               column.getCollationName());
            }
        }
        builder.foreignKey(referencingSchemaName, referencingTableName,
                           Arrays.asList(referencingColumnNames),
                           referencedName.getSchemaName(), referencedName.getTableName(),
                           Arrays.asList(referencedColumnNames),
                           convertReferentialAction(fkdn.getRefActionDeleteRule()),
                           convertReferentialAction(fkdn.getRefActionUpdateRule()),
                           fkdn.isDeferrable(), fkdn.isInitiallyDeferred(),
                           constraintName);
    }

    private static ForeignKey.Action convertReferentialAction(int action) {
        switch (action) {
        case StatementType.RA_NOACTION:
        default:
            return ForeignKey.Action.NO_ACTION;
        case StatementType.RA_RESTRICT:
            return ForeignKey.Action.RESTRICT;
        case StatementType.RA_CASCADE:
            return ForeignKey.Action.CASCADE;
        case StatementType.RA_SETNULL:
            return ForeignKey.Action.SET_NULL;
        case StatementType.RA_SETDEFAULT:
            return ForeignKey.Action.SET_DEFAULT;
        }
    }
        
}
