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

package com.foundationdb.server.test.it.qp;

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.qp.expression.IndexBound;
import com.foundationdb.qp.expression.IndexKeyRange;
import com.foundationdb.qp.operator.API;
import com.foundationdb.qp.operator.API.IntersectOption;
import com.foundationdb.qp.operator.API.JoinType;
import com.foundationdb.qp.operator.Cursor;
import com.foundationdb.qp.operator.Operator;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.operator.SimpleQueryContext;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.rowtype.IndexRowType;
import com.foundationdb.qp.rowtype.Schema;
import com.foundationdb.qp.util.SchemaCache;
import com.foundationdb.server.api.dml.SetColumnSelector;
import com.foundationdb.server.test.it.ITBase;
import com.foundationdb.server.types.TKeyComparable;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Testing nested intersects across differing types (BIGINT -> INT).
 * Intersect_Ordered wasn't initially using the TComparisons list when comparing the skip row, leading to an error.
 */
public class Intersect_Ordered_BigIntIntIT extends ITBase
{
    private static final String SCHEMA = "test";
    private static final String REPORTS = "reports";
    private static final String METADATA = "metadata";

    @Test
    public void test() {
        int rid = createTable(SCHEMA, REPORTS,
                              "id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(255)");
        createIndex(SCHEMA, REPORTS, "name", "name");
        int mid = createTable(SCHEMA, METADATA,
                              "id BIGINT NOT NULL PRIMARY KEY, report_id INT, name VARCHAR(255), value VARCHAR(255)",
                              "GROUPING FOREIGN KEY(report_id) REFERENCES reports(id)");
        createIndex(SCHEMA, METADATA, "name_value", "name", "value");

        writeRows(
            createNewRow(rid, 1, "foo"),
            createNewRow(mid, 10, 1, "x", "one"),
            createNewRow(rid, 2, "bar"),
            createNewRow(mid, 20, 2, "x", "one"),
            createNewRow(rid, 3, "foo"),
            createNewRow(mid, 30, 3, "x", "one"),
            createNewRow(mid, 31, 3, "y", "two"),
            createNewRow(rid, 4, "zap"),
            createNewRow(mid, 40, 4, "x", "one"),
            createNewRow(mid, 41, 4, "y", "two")
        );

        AkibanInformationSchema ais = ais();
        Schema schema = SchemaCache.globalSchema(ais);
        IndexRowType rIndex = schema.indexRowType(ais.getTable(rid).getIndex("name"));
        IndexRowType mdIndex = schema.indexRowType(ais.getTable(mid).getIndex("name_value"));

        /*
        Build something like:
          Intersect_Ordered(skip 2 left, skip 2 right, compare 1)
            Intersect_Ordered(skip 2 left, skip 1 right, compare 1)
              IndexScan_Default(Index(metadata.name_value), name = 'x', value = 'one')
              IndexScan_Default(Index(reports.name), name = 'foo')
            IndexScan_Default(Index(metadata.name_value), name = 'y', value = 'two')
         */

        IndexBound fooBound = new IndexBound(testRow(rIndex, "foo", null), new SetColumnSelector(0));
        Operator fooScan = API.indexScan_Default(rIndex,
                                                 false,
                                                 IndexKeyRange.bounded(rIndex, fooBound, true, fooBound, true));

        IndexBound xOneBound = new IndexBound(testRow(mdIndex, "x", "one", null, null), new SetColumnSelector(0, 1));
        Operator xOneScan = API.indexScan_Default(mdIndex,
                                                   false,
                                                   IndexKeyRange.bounded(mdIndex, xOneBound, true, xOneBound, true));

        IndexBound yTwoBound = new IndexBound(testRow(mdIndex, "y", "two", null, null), new SetColumnSelector(0, 1));
        Operator yTwoScan = API.indexScan_Default(mdIndex,
                                                  false,
                                                  IndexKeyRange.bounded(mdIndex, yTwoBound, true, yTwoBound, true));

        TKeyComparable comparableIntBigint = typesRegistryService().getKeyComparable(
            ais.getTable(mid).getColumn("report_id").getType().typeClass(),
            ais.getTable(rid).getColumn("id").getType().typeClass()
        );

        Operator innerIntersect = API.intersectAll_Ordered(
                xOneScan,
                fooScan,
                mdIndex,
                rIndex,
                mdIndex.index().getAllColumns().size() - 2,
                rIndex.index().getAllColumns().size() - 1,
                new boolean[]{true},
                JoinType.INNER_JOIN,
                EnumSet.of(IntersectOption.OUTPUT_LEFT, IntersectOption.SKIP_SCAN),
                Arrays.asList(comparableIntBigint.getComparison())
        );

        Operator outerIntersect = API.intersectAll_Ordered(
                innerIntersect,
                yTwoScan,
                mdIndex,
                mdIndex,
                mdIndex.index().getAllColumns().size() - 2,
                mdIndex.index().getAllColumns().size() - 2,
                new boolean[]{true},
                JoinType.INNER_JOIN,
                EnumSet.of(IntersectOption.OUTPUT_LEFT, IntersectOption.SKIP_SCAN),
                null
        );

        QueryContext context = new SimpleQueryContext(newStoreAdapter(schema));
        Cursor cursor = API.cursor(outerIntersect, context, context.createBindings());
        compareRows(
            new Row[] {
                testRow(mdIndex, "x", "one", 3, 30)
            },
            cursor
        );
    }
}
