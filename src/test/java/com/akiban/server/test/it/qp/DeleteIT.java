/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
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

package com.akiban.server.test.it.qp;

import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.exec.UpdateResult;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.row.BindableRow;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.types.AkType;
import com.akiban.server.types3.Types3Switch;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.akiban.qp.operator.API.cursor;
import static com.akiban.qp.operator.API.delete_Default;
import static com.akiban.qp.operator.API.filter_Default;
import static com.akiban.qp.operator.API.groupScan_Default;
import static com.akiban.qp.operator.API.indexScan_Default;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class DeleteIT extends OperatorITBase {
    @Test
    public void deleteCustomer() {
        use(db);
        doDelete();
        compareRows(
                array(TestRow.class,
                      row(customerRowType, 1L, "xyz")
                ),
                cursor(
                        filter_Default(
                                groupScan_Default(coi),
                                Collections.singleton(customerRowType)),
                        queryContext, queryBindings
                )
        );
    }

    @Test
    public void deleteCustomerCheckNameIndex() {
        use(db);
        doDelete();
        compareRows(
                array(RowBase.class,
                      row(customerNameIndexRowType, "xyz", 1L)
                      ),
                cursor(
                indexScan_Default(
                        customerNameIndexRowType,
                        IndexKeyRange.unbounded(customerNameIndexRowType),
                        new API.Ordering()),
                queryContext, queryBindings
        ));
    }

    @Test
    public void deleteCustomerCheckNameItemOidGroupIndex() {
        use(db);
        doDelete();
        compareRows(
                array(RowBase.class,
                      row(customerNameItemOidIndexRowType, "xyz", 11L, 1L, 11L, 111L),
                      row(customerNameItemOidIndexRowType, "xyz", 11L, 1L, 11L, 112L),
                      row(customerNameItemOidIndexRowType, "xyz", 12L, 1L, 12L, 121L),
                      row(customerNameItemOidIndexRowType, "xyz", 12L, 1L, 12L, 122L)
                ),
                cursor(
                indexScan_Default(
                        customerNameItemOidIndexRowType,
                        IndexKeyRange.unbounded(customerNameItemOidIndexRowType),
                        new API.Ordering(),
                        customerRowType),
                queryContext, queryBindings
        ));
    }

    private void doDelete() {
        Row[] rows = {
                row(customerRowType, new Object[]{2, "abc"}, new AkType[]{AkType.INT, AkType.VARCHAR})
        };
        UpdatePlannable insertPlan = delete_Default(rowsToValueScan(rows));
        UpdateResult result = insertPlan.run(queryContext, queryBindings);
        assertEquals("rows touched", rows.length, result.rowsTouched());
        assertEquals("rows modified", rows.length, result.rowsModified());
    }
}