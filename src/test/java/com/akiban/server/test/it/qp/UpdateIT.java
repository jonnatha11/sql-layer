/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.test.it.qp;

import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.exec.UpdateResult;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.UpdateFunction;
import com.akiban.qp.row.OverlayingRow;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowBase;
import com.akiban.server.types.AkType;
import com.akiban.server.types.ToObjectValueTarget;
import com.akiban.server.types.conversion.Converters;
import static com.akiban.qp.operator.API.*;

import com.persistit.Transaction;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

public class UpdateIT extends OperatorITBase
{
    @Test
    public void basicUpdate() throws Exception {
        use(db);

        UpdateFunction updateFunction = new UpdateFunction() {
            @Override
            public boolean rowIsSelected(Row row) {
                return row.rowType().equals(customerRowType);
            }

            @Override
            public Row evaluate(Row original, QueryContext context) {
                ToObjectValueTarget target = new ToObjectValueTarget();
                target.expectType(AkType.VARCHAR);
                Object obj = Converters.convert(original.eval(1), target).lastConvertedValue();
                String name = (String) obj; // TODO eventually use Expression for this
                name = name.toUpperCase();
                name = name + name;
                return new OverlayingRow(original).overlay(1, name);
            }
        };

        Operator groupScan = groupScan_Default(coi);
        UpdatePlannable updateOperator = update_Default(groupScan, updateFunction);
        UpdateResult result = updateOperator.run(queryContext);
        assertEquals("rows modified", 2, result.rowsModified());
        assertEquals("rows touched", db.length, result.rowsTouched());

        Cursor executable = cursor(groupScan, queryContext);
        RowBase[] expected = new RowBase[]{row(customerRowType, 1L, "XYZXYZ"),
                                           row(orderRowType, 11L, 1L, "ori"),
                                           row(itemRowType, 111L, 11L),
                                           row(itemRowType, 112L, 11L),
                                           row(orderRowType, 12L, 1L, "david"),
                                           row(itemRowType, 121L, 12L),
                                           row(itemRowType, 122L, 12L),
                                           row(customerRowType, 2L, "ABCABC"),
                                           row(orderRowType, 21L, 2L, "tom"),
                                           row(itemRowType, 211L, 21L),
                                           row(itemRowType, 212L, 21L),
                                           row(orderRowType, 22L, 2L, "jack"),
                                           row(itemRowType, 221L, 22L),
                                           row(itemRowType, 222L, 22L)
        };
        compareRows(expected, executable);
    }
    
    @Test
    public void changePrimaryKeys() throws Exception {
        use(db);

        Operator scan = filter_Default(
            ancestorLookup_Default(
                indexScan_Default(itemIidIndexRowType),
                coi,
                itemIidIndexRowType,
                Arrays.asList(itemRowType),
                InputPreservationOption.DISCARD_INPUT),
            Arrays.asList(itemRowType));
        
        UpdateFunction updateFunction = new UpdateFunction() {
                @Override
                public boolean rowIsSelected(Row row) {
                    return row.rowType().equals(itemRowType);
                }

                @Override
                public Row evaluate(Row original, QueryContext context) {
                    long id = original.eval(0).getInt();
                    // Make smaller to avoid Halloween (see next test).
                    return new OverlayingRow(original).overlay(0, id - 100);
                }
            };

        UpdatePlannable updateOperator = update_Default(scan, updateFunction);
        UpdateResult result = updateOperator.run(queryContext);
        assertEquals("rows touched", 8, result.rowsTouched());
        assertEquals("rows modified", 8, result.rowsModified());

        Cursor executable = cursor(scan, queryContext);
        RowBase[] expected = new RowBase[] { 
            row(itemRowType, 11L, 11L),
            row(itemRowType, 12L, 11L),
            row(itemRowType, 21L, 12L),
            row(itemRowType, 22L, 12L),
            row(itemRowType, 111L, 21L),
            row(itemRowType, 112L, 21L),
            row(itemRowType, 121L, 22L),
            row(itemRowType, 122L, 22L),
        };
        compareRows(expected, executable);
    }

    @Test
    // http://en.wikipedia.org/wiki/Halloween_Problem
    public void halloweenProblem() throws Exception {
        use(db);
        Transaction transaction = adapter.transaction();
        transaction.incrementStep(); // Enter isolation mode.

        Operator scan = filter_Default(
            ancestorLookup_Default(
                indexScan_Default(itemIidIndexRowType),
                coi,
                itemIidIndexRowType,
                Arrays.asList(itemRowType),
                InputPreservationOption.DISCARD_INPUT),
            Arrays.asList(itemRowType));
        
        UpdateFunction updateFunction = new UpdateFunction() {
                @Override
                public boolean rowIsSelected(Row row) {
                    return row.rowType().equals(itemRowType);
                }

                @Override
                public Row evaluate(Row original, QueryContext context) {
                    long id = original.eval(0).getInt();
                    return new OverlayingRow(original).overlay(0, 1000 + id);
                }
            };

        UpdatePlannable updateOperator = update_Default(scan, updateFunction);
        UpdateResult result = updateOperator.run(queryContext);
        assertEquals("rows touched", 8, result.rowsTouched());
        assertEquals("rows modified", 8, result.rowsModified());

        transaction.incrementStep(); // Make changes visible.

        Cursor executable = cursor(scan, queryContext);
        RowBase[] expected = new RowBase[] { 
            row(itemRowType, 1111L, 11L),
            row(itemRowType, 1112L, 11L),
            row(itemRowType, 1121L, 12L),
            row(itemRowType, 1122L, 12L),
            row(itemRowType, 1211L, 21L),
            row(itemRowType, 1212L, 21L),
            row(itemRowType, 1221L, 22L),
            row(itemRowType, 1222L, 22L),
        };
        compareRows(expected, executable);
    }

}
