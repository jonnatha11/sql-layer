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

package com.foundationdb.server.store.format.tuple;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.Join;
import com.foundationdb.ais.model.PrimaryKey;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.protobuf.FDBProtobuf.TupleUsage;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.row.ValuesHolderRow;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.server.types.TClass;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.server.types.aksql.aktypes.AkBool;
import com.foundationdb.server.types.aksql.aktypes.AkGUID;
import com.foundationdb.server.types.aksql.aktypes.AkBlob;
import com.foundationdb.server.types.mcompat.mtypes.MApproximateNumber;
import com.foundationdb.server.types.mcompat.mtypes.MBinary;
import com.foundationdb.server.types.mcompat.mtypes.MDateAndTime;
import com.foundationdb.server.types.mcompat.mtypes.MNumeric;
import com.foundationdb.server.types.mcompat.mtypes.MString;
import com.foundationdb.server.types.value.ValueSources;
import com.foundationdb.tuple.Tuple2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TupleRowDataConverter
{
    private static final Logger LOG = LoggerFactory.getLogger(TupleRowDataConverter.class);

    static final Set<TClass> ALLOWED_CLASSES = new HashSet<>(Arrays.asList(
        MNumeric.BIGINT, MNumeric.BIGINT_UNSIGNED, MNumeric.INT, MNumeric.INT_UNSIGNED,
        MNumeric.MEDIUMINT, MNumeric.MEDIUMINT_UNSIGNED, MNumeric.SMALLINT,
        MNumeric.SMALLINT_UNSIGNED, MNumeric.TINYINT, MNumeric.TINYINT_UNSIGNED,
        MNumeric.DECIMAL, MNumeric.DECIMAL_UNSIGNED, MApproximateNumber.DOUBLE,
        MApproximateNumber.DOUBLE_UNSIGNED, MApproximateNumber.FLOAT, MApproximateNumber.FLOAT_UNSIGNED,
        MBinary.VARBINARY, MBinary.BINARY, MString.VARCHAR, MString.CHAR,
        MString.TINYTEXT, MString.TEXT,
        MString.MEDIUMTEXT, MString.LONGTEXT,
        MDateAndTime.TIMESTAMP, MDateAndTime.DATE, MDateAndTime.TIME, MDateAndTime.DATETIME,
        MDateAndTime.YEAR, AkGUID.INSTANCE, AkBool.INSTANCE, AkBlob.INSTANCE
    ));

    protected static void checkColumn(Column column, List<String> illegal) {
        if (!ALLOWED_CLASSES.contains(TInstance.tClass(column.getType()))) {
            illegal.add(column.toString());
        }
    }

    protected static void checkTable(Table table, TupleUsage usage, 
                                     List<String> illegal) {
        switch (usage) {
        case KEY_ONLY:
            PrimaryKey pkey = table.getPrimaryKeyIncludingInternal();
            for (Column column : pkey.getColumns()) {
                checkColumn(column, illegal);
            }
            break;
        case KEY_AND_ROW:
            for (Column column : table.getColumnsIncludingInternal()) {
                checkColumn(column, illegal);
            }
            break;
        }
        for (Join join : table.getChildJoins()) {
            checkTable(join.getChild(), usage, illegal);
        }
    }

    public static List<String> checkTypes(Group group, TupleUsage usage) {
        List<String> illegal = new ArrayList<>();
        checkTable(group.getRoot(), usage, illegal);
        return illegal;
    }

    public static List<String> checkTypes(Index index, TupleUsage usage) {
        List<String> illegal = new ArrayList<>();
        for (IndexColumn indexColumn : index.getKeyColumns()) {
            checkColumn(indexColumn.getColumn(), illegal);
        }
        return illegal;
    }

    public static Tuple2 tupleFromRow (Row row) {
        int nfields = row.rowType().nFields();
        assert nfields == row.rowType().table().getColumnsIncludingInternal().size() : 
             "Row Type: " + nfields + " Vs. table: " + row.rowType().table();
        Object[] objects = new Object[nfields];
        for (int i = 0; i < nfields ; i++) {
            objects[i] = ValueSources.toObject(row.value(i));
        }
        return Tuple2.from(objects);
    }
    
    public static Row tupleToRow (Tuple2 tuple, RowType rowType) {
        int nfields = rowType.nFields();
        Object[] objects = new Object[nfields];
        assert tuple.size() == nfields : "Row Type " + rowType + " does not match tuple size: " + tuple.size();
        for (int i = 0; i < nfields; i++) {
            objects[i] = tuple.get(i);
        }
        ValuesHolderRow newRow = new ValuesHolderRow (rowType, objects);
        return newRow;
    }
}
