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

package com.foundationdb.ais.model;

import com.foundationdb.ais.model.validation.AISInvariants;
import com.foundationdb.qp.storeadapter.SpatialHelper;
import com.foundationdb.server.geophile.Space;
import com.foundationdb.server.geophile.SpaceLatLon;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.server.types.common.types.TBigDecimal;
import com.foundationdb.server.types.mcompat.mtypes.MNumeric;

import java.util.*;

public abstract class Index extends HasStorage implements Visitable, Constraint
{
    public abstract HKey hKey();
    public abstract boolean isTableIndex();
    public abstract void computeFieldAssociations(Map<Table,Integer> ordinalMap);
    public abstract Table leafMostTable();
    public abstract Table rootMostTable();
    public abstract void checkMutability();
    public abstract Collection<Integer> getAllTableIDs();

    protected Index(TableName tableName,
                    String indexName,
                    Integer indexId,
                    Boolean isUnique,
                    Boolean isPrimary,
                    TableName constraintName,
                    JoinType joinType,
                    boolean isValid)
    {
        if ( (indexId != null) && (indexId | INDEX_ID_BITS) != INDEX_ID_BITS)
            throw new IllegalArgumentException("index ID out of range: " + indexId + " > " + INDEX_ID_BITS);
        AISInvariants.checkNullName(indexName, "index", "index name");

        if(((isUnique == null) || !isUnique) && (constraintName != null)) {
            throw new IllegalStateException("Unexpected constraint name for non-unique index: " + indexName);
        }

        this.indexName = new IndexName(tableName, indexName);
        this.indexId = indexId;
        this.isUnique = isUnique;
        this.isPrimary = isPrimary;
        this.joinType = joinType;
        this.isValid = isValid;
        this.constraintName = constraintName;
        keyColumns = new ArrayList<>();
    }

    protected Index(TableName tableName, String indexName, Integer idAndFlags, Boolean isUnique, Boolean isPrimary, TableName constraintName) {
        this (
                tableName,
                indexName,
                extractIndexId(idAndFlags),
                isUnique,
                isPrimary,
                constraintName,
                extractJoinType(idAndFlags),
                extractIsValid(idAndFlags)
        );
    }

    public boolean isGroupIndex()
    {
        return !isTableIndex();
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public boolean isValid() {
        return isTableIndex() || isValid;
    }

    @Override
    public String toString()
    {
        StringBuilder buffer = new StringBuilder();
        buffer.append(getTypeString());
        buffer.append("(");
        buffer.append(getNameString());
        buffer.append(keyColumns.toString());
        buffer.append(")");
        if (space != null) {
            buffer.append(space.toString());
        }
        return buffer.toString();
    }

    void addColumn(IndexColumn indexColumn)
    {
        if (columnsFrozen) {
            throw new IllegalStateException("can't add column because columns list is frozen");
        }
        keyColumns.add(indexColumn);
        columnsStale = true;
    }

    public void freezeColumns() {
        if (!columnsFrozen) {
            sortColumnsIfNeeded();
            columnsFrozen = true;
        }
    }

    public boolean isUnique()
    {
        return isUnique;
    }

    public boolean isPrimaryKey()
    {
        return isPrimary;
    }

    public boolean isConnectedToFK(Schema schema) {
        if (schema.hasConstraint(indexName.getName()) && (schema.getConstraint(indexName.getName()) instanceof ForeignKey)) {
            return true;
        }
        return false;
    }

    public IndexName getIndexName()
    {
        return indexName;
    }

    public void setIndexName(IndexName name)
    {
        indexName = name;
    }

    /**
     * Return columns declared as part of the index definition.
     * @return list of columns
     */
    public List<IndexColumn> getKeyColumns()
    {
        sortColumnsIfNeeded();
        return keyColumns;
    }

    /**
     * Return all columns that make up the physical index key. This includes declared columns and hkey columns.
     * @return list of columns
     */
    public List<IndexColumn> getAllColumns() {
        return allColumns;
    }

    public IndexMethod getIndexMethod()
    {
        if (space != null)
            return IndexMethod.Z_ORDER_LAT_LON;
        else
            return IndexMethod.NORMAL;
    }

    public void markSpatial(int firstSpatialArgument, int dimensions)
    {
        checkMutability();
        if (dimensions != Space.LAT_LON_DIMENSIONS) {
            // Only lat/lon for now
            throw new IllegalArgumentException();
        }
        this.firstSpatialArgument = firstSpatialArgument;
        this.space = SpaceLatLon.create();
    }

    public int firstSpatialArgument()
    {
        return firstSpatialArgument;
    }

    public int dimensions()
    {
        // Only lat/lon for now
        return Space.LAT_LON_DIMENSIONS;
    }

    public Space space()
    {
        return space;
    }

    public final boolean isSpatial()
    {
        switch (getIndexMethod()) {
        case Z_ORDER_LAT_LON:
            return true;
        default:
            return false;
        }
    }

    private void sortColumnsIfNeeded() {
        if (columnsStale) {
            Collections.sort(keyColumns,
                    new Comparator<IndexColumn>() {
                        @Override
                        public int compare(IndexColumn x, IndexColumn y) {
                            return x.getPosition() - y.getPosition();
                        }
                    });
            columnsStale = false;
        }
    }

    public Integer getIndexId()
    {
        return indexId;
    }

    public void setIndexId(Integer indexId)
    {
        this.indexId = indexId;
    }

    public IndexType getIndexType()
    {
        return isTableIndex() ? IndexType.TABLE : IndexType.GROUP;
    }

    public IndexRowComposition indexRowComposition()
    {
        return indexRowComposition;
    }

    protected static class AssociationBuilder {
        /**
         * @param fieldPosition entry of {@link IndexRowComposition#fieldPositions}
         * @param hkeyPosition entry of {@link IndexRowComposition#hkeyPositions}
         */
        void rowCompEntry(int fieldPosition, int hkeyPosition) {
            list1.add(fieldPosition); list2.add(hkeyPosition);
        }

        /**
         * @param ordinal entry of {@link IndexToHKey#ordinals}
         * @param indexRowPosition entry of {@link IndexToHKey#indexRowPositions}
         */
        void toHKeyEntry(int ordinal, int indexRowPosition) {
            list1.add(ordinal); list2.add(indexRowPosition);
        }

        IndexRowComposition createIndexRowComposition() {
            return new IndexRowComposition(asArray(list1), asArray(list2));
        }

        IndexToHKey createIndexToHKey() {
            return new IndexToHKey(asArray(list1), asArray(list2));
        }

        private int[] asArray(List<Integer> list) {
            int[] array = new int[list.size()];
            for(int i = 0; i < list.size(); ++i) {
                array[i] = list.get(i);
            }
            return array;
        }

        private List<Integer> list1 = new ArrayList<>();
        private List<Integer> list2 = new ArrayList<>();
    }
    
    private static JoinType extractJoinType(Integer idAndFlags) {
        if (idAndFlags == null)
            return  null;
        return (idAndFlags & IS_RIGHT_JOIN_FLAG) == IS_RIGHT_JOIN_FLAG
                ? JoinType.RIGHT
                : JoinType.LEFT;
    }

    private static boolean extractIsValid(Integer idAndFlags) {
        return idAndFlags != null && (idAndFlags & IS_VALID_FLAG) == IS_VALID_FLAG;
    }

    private static Integer extractIndexId(Integer idAndFlags) {
        if (idAndFlags == null)
            return null;
        if (idAndFlags < 0)
            throw new IllegalArgumentException("Negative idAndFlags: " + idAndFlags);
        return idAndFlags & INDEX_ID_BITS;
    }

    public Integer getIdAndFlags() {
        if(indexId == null) {
            return null;
        }
        int idAndFlags = indexId;
        if(isValid) {
            idAndFlags |= IS_VALID_FLAG;
        }
        if(joinType == JoinType.RIGHT) {
            idAndFlags |= IS_RIGHT_JOIN_FLAG;
        }
        return idAndFlags;
    }

    public boolean containsTableColumn(TableName tableName, String columnName) {
        for(IndexColumn iCol : keyColumns) {
            Column column = iCol.getColumn();
            if(column.getTable().getName().equals(tableName) && column.getName().equals(columnName)) {
                return true;
            }
        }
        return false;
    }

    // Visitable

    /** Visit this instance and then all index columns. */
    @Override
    public void visit(Visitor visitor) {
        visitor.visit(this);
        // Not present until computeFieldAssociations is called
        List<IndexColumn> cols = (allColumns == null) ? keyColumns : allColumns;
        for(IndexColumn ic : cols) {
            ic.visit(visitor);
        }
    }

    // akCollators and types provide type info for physical index rows.
    // Physical != logical for spatial indexes.

    public TInstance[] types()
    {
        ensureTypeInfo();
        return types;
    }

    private void ensureTypeInfo()
    {
        if (types == null) {
            synchronized (this) {
                if (types == null) {
                    int physicalColumns;
                    int firstSpatialColumn;
                    int dimensions;
                    if (isSpatial()) {
                        dimensions = dimensions();
                        physicalColumns = allColumns.size() - dimensions + 1;
                        firstSpatialColumn = firstSpatialArgument();
                    } else {
                        dimensions = 0;
                        physicalColumns = allColumns.size();
                        firstSpatialColumn = Integer.MAX_VALUE;
                    }
                    TInstance[] localTInstances = null;
                    localTInstances = new TInstance[physicalColumns];
                    int logicalColumn = 0;
                    int physicalColumn = 0;
                    int nColumns = allColumns.size();
                    while (logicalColumn < nColumns) {
                        if (logicalColumn == firstSpatialColumn) {
                            localTInstances[physicalColumn] =
                                MNumeric.BIGINT.instance(SpatialHelper.isNullable(this));
                            logicalColumn += dimensions;
                        } else {
                            IndexColumn indexColumn = allColumns.get(logicalColumn);
                            Column column = indexColumn.getColumn();
                            localTInstances[physicalColumn] = column.getType();
                            logicalColumn++;
                        }
                        physicalColumn++;
                    }
                    types = localTInstances;
                }
            }
        }
     }

    public static boolean isSpatialCompatible(Index index)
    {
        boolean isSpatialCompatible = false;
        List<IndexColumn> indexColumns = index.getKeyColumns();
        if (indexColumns.size() >= Space.LAT_LON_DIMENSIONS) {
            isSpatialCompatible = true;
            for (int d = 0; d < index.dimensions(); d++) {
                isSpatialCompatible =
                    isSpatialCompatible &&
                    isFixedDecimal(indexColumns.get(index.firstSpatialArgument() + d).getColumn());
            }
        }
        return isSpatialCompatible;
    }

    private static boolean isFixedDecimal(Column column) {
        return column.getType().typeClass() instanceof TBigDecimal;
    }
    public static final String PRIMARY = "PRIMARY";
    private static final int INDEX_ID_BITS = 0x0000FFFF;
    private static final int IS_VALID_FLAG = INDEX_ID_BITS + 1;
    private static final int IS_RIGHT_JOIN_FLAG = IS_VALID_FLAG << 1;
    private final Boolean isUnique;
    private final Boolean isPrimary;
    private final JoinType joinType;
    private final boolean isValid;
    private Integer indexId;
    private IndexName indexName;
    private boolean columnsStale = true;
    private boolean columnsFrozen = false;
    protected IndexRowComposition indexRowComposition;
    protected List<IndexColumn> keyColumns;
    protected List<IndexColumn> allColumns;
    private volatile TInstance[] types;
    private TableName constraintName;
    // For a spatial index
    private Space space;
    private int firstSpatialArgument;

    public enum JoinType {
        LEFT, RIGHT
    }

    public static enum IndexType {
        TABLE("TABLE"),
        GROUP("GROUP"),
        FULL_TEXT("FULL_TEXT")
        ;

        private IndexType(String asString) {
            this.asString = asString;
        }

        @Override
        public final String toString() {
            return asString;
        }

        private final String asString;
    }

    public enum IndexMethod {
        NORMAL, Z_ORDER_LAT_LON, FULL_TEXT
    }

    // HasStorage

    @Override
    public AkibanInformationSchema getAIS() {
        return leafMostTable().getAIS();
    }

    @Override
    public String getTypeString() {
        return "Index";
    }

    @Override
    public String getNameString() {
        return indexName.toString();
    }

    @Override
    public String getSchemaName() {
        return indexName.getSchemaName();
    }
    
    // constraint
    
    public TableName getConstraintName() {
        return constraintName;
    }

}
