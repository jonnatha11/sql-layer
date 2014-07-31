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

package com.foundationdb.qp.operator;

import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.qp.operator.HashJoin.KeyWrapper;
import com.foundationdb.server.collation.AkCollator;
import com.foundationdb.server.explain.*;
import com.foundationdb.util.ArgumentValidation;
import com.foundationdb.util.tap.InOutTap;
import com.google.common.collect.ArrayListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;


class Using_HashJoin extends Operator
{
    // Object interface

    @Override
    public String toString()
    {
        return getClass().getSimpleName();
    }

    // Operator interface


    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes)
    {
        hashInput.findDerivedTypes(derivedTypes);
        joinedInput.findDerivedTypes(derivedTypes);
    }

    @Override
    protected Cursor cursor(QueryContext context, QueryBindingsCursor bindingsCursor)
    {
        return new Execution(context, joinedInput.cursor(context, bindingsCursor));
    }

    @Override
    public List<Operator> getInputOperators()
    {
        return Arrays.asList(hashInput, joinedInput);
    }

    @Override
    public String describePlan()
    {
        return String.format("%s\n%s", describePlan(hashInput), describePlan(joinedInput));
    }

    public Using_HashJoin(Operator hashInput,
                          int comparisonFields[],
                          int tableBindingPosition,
                          Operator joinedInput,
                          List<AkCollator> collators)
    {
        ArgumentValidation.notNull("hashInput", hashInput);
        ArgumentValidation.notNull("comparisonFields", comparisonFields);
        ArgumentValidation.isGTE("comparisonFields", comparisonFields.length, 1);
        ArgumentValidation.notNull("joinedInput", joinedInput);

        this.hashInput = hashInput;
        this.comparisonFields = comparisonFields;
        this.tableBindingPosition = tableBindingPosition;
        this.joinedInput = joinedInput;
        this.collators = collators;
    }

    // For use by this class

    private AkCollator collator(int f)
    {
        return collators == null ? null : collators.get(f);
    }

    // Class state

    private static final InOutTap TAP_OPEN = OPERATOR_TAP.createSubsidiaryTap("operator: Using_HashJoin open");
    private static final InOutTap TAP_NEXT = OPERATOR_TAP.createSubsidiaryTap("operator: Using_HashJoin next");
    private static final Logger LOG = LoggerFactory.getLogger(Using_HashJoin.class);

    // Object state

    private final Operator hashInput;
    private final int comparisonFields[];
    private final int tableBindingPosition;
    private final Operator joinedInput;
    private final List<AkCollator> collators;


    @Override
    public CompoundExplainer getExplainer(ExplainContext context) {
        Attributes atts = new Attributes();
        atts.put(Label.NAME, PrimitiveExplainer.getInstance(getName()));
        atts.put(Label.BINDING_POSITION, PrimitiveExplainer.getInstance(tableBindingPosition));
        atts.put(Label.INPUT_OPERATOR, hashInput.getExplainer(context));
        atts.put(Label.INPUT_OPERATOR, joinedInput.getExplainer(context));
        return new CompoundExplainer(Type.HASH_JOIN, atts);
    }

    // Inner classes

    private class Execution extends ChainedCursor
    {
        // Cursor interface

        @Override
        public void open()
        {
            TAP_OPEN.in();
            try {
                ArrayListMultimap<KeyWrapper, Row> hashTable = buildHashTable();
                bindings.setHashJoinTable(tableBindingPosition, hashTable);
                input.open();
            } finally {
                TAP_OPEN.out();
            }
        }

        @Override
        public Row next()
        {
            if (TAP_NEXT_ENABLED) {
                TAP_NEXT.in();
            }
            try {
                Row output = input.next();
                if (LOG_EXECUTION) {
                    LOG.debug("Using_HashJoin: yield {}", output);
                }
                return output;
            } finally {
                if (TAP_NEXT_ENABLED) {
                    TAP_NEXT.out();
                }
            }
        }

        @Override
        public void destroy()
        {
            close();
            input.destroy();
            if (bindings != null) {
                bindings.setHashJoinTable(tableBindingPosition, null);
            }
        }

        // Execution interface

        Execution(QueryContext context, Cursor input)
        {
            super(context, input);
        }

        // For use by this classb



        private ArrayListMultimap<KeyWrapper, Row>  buildHashTable() {
            QueryBindingsCursor bindingsCursor = new SingletonQueryBindingsCursor(bindings);
            Cursor loadCursor = hashInput.cursor(context, bindingsCursor);
            loadCursor.openTopLevel();
            ArrayListMultimap<KeyWrapper, Row> hashTable = ArrayListMultimap.create();
            Row row;
            KeyWrapper keyWrapper;
            while ((row = loadCursor.next()) != null) {
                keyWrapper = new KeyWrapper(row, comparisonFields, collators);
                hashTable.put(keyWrapper, row);
            }
            loadCursor.destroy();
            return hashTable;
        }
     }
}
