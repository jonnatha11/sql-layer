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

import com.foundationdb.qp.row.*;
import com.foundationdb.qp.rowtype.*;
import com.foundationdb.server.collation.AkCollator;
import com.foundationdb.server.explain.*;
import com.foundationdb.server.types.value.ValueSource;
import com.foundationdb.server.types.value.ValueSources;
import com.foundationdb.util.ArgumentValidation;
import com.foundationdb.util.tap.InOutTap;
import com.google.common.collect.ArrayListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

class HashJoin extends Operator
{
    @Override
    public String toString()
    {
        return "HashJoin";
    }

    // Operator interface

    @Override
    protected Cursor cursor(QueryContext context, QueryBindingsCursor bindingsCursor)
    {
        return new Execution(context, bindingsCursor);
    }

    public HashJoin(List<AkCollator> collators,
                    int outerComparisonFields[],
                    boolean outerLeftJoin,
                    int hashBindingPosition,
                    int rowBindingPosition
                    )
    {
        ArgumentValidation.notNull("outerComparisonFields", outerComparisonFields);
        ArgumentValidation.isGTE("outerOrderingFields", outerComparisonFields.length, 1);
        ArgumentValidation.isNotSame("hashBindingPosition", hashBindingPosition,"rowBindingPosition", rowBindingPosition);

        this.collators = collators;
        this.outerComparisonFields = outerComparisonFields;
        this.outerLeftJoin = outerLeftJoin;
        this.hashBindingPosition = hashBindingPosition;
        this.rowBindingPosition = rowBindingPosition;

    }

    // Class state

    private static final InOutTap TAP_OPEN = OPERATOR_TAP.createSubsidiaryTap("operator: intersect_Ordered open");
    private static final InOutTap TAP_NEXT = OPERATOR_TAP.createSubsidiaryTap("operator: intersect_Ordered next");
    private static final Logger LOG = LoggerFactory.getLogger(Intersect_Ordered.class);

    // Object state

    private final int outerComparisonFields[];
    private final int hashBindingPosition;
    private final int rowBindingPosition;

    private final List<AkCollator> collators;
    private final boolean outerLeftJoin;

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        Attributes atts = new Attributes();
        atts.put(Label.NAME, PrimitiveExplainer.getInstance(getName()));
        return new CompoundExplainer(Type.HASH_JOIN, atts);
    }

    private class Execution extends OperatorCursor
    {

        @Override
        public void open(){
            TAP_OPEN.in();
            try {
                CursorLifecycle.checkIdle(this);
                hashTable = bindings.getHashJoinTable(hashBindingPosition);
                innerRowList = hashTable.get(new KeyWrapper(getOuterRow(), outerComparisonFields, collators));
                innerRowListPosition = 0;
                closed = false;
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
                if (CURSOR_LIFECYCLE_ENABLED) {
                    CursorLifecycle.checkIdleOrActive(this);
                }
                Row next = null;
                if(innerRowListPosition < innerRowList.size()) {
                    next = innerRowList.get(innerRowListPosition++);
                } else if(outerLeftJoin && innerRowListPosition++ == 0){
                    next = getOuterRow();
                }
                if (LOG_EXECUTION) {
                    LOG.debug("HashJoin: yield {}", next);
                }
                return next;
            } finally {
                if (TAP_NEXT_ENABLED) {
                    TAP_NEXT.out();
                }
            }
        }

        @Override
        public void close()
        {
            CursorLifecycle.checkIdleOrActive(this);
            if (!closed) {
                closed = true;
            }
        }

        @Override
        public void closeBindings() {
            bindingsCursor.closeBindings();
        }

        @Override
        public void destroy()
        {
            close();
            destroyed = true;
        }

        @Override
        public boolean isIdle()
        {
            return closed;
        }

        @Override
        public boolean isActive()
        {
            return !closed;
        }

        @Override
        public void openBindings() {
            bindingsCursor.openBindings();
        }

        @Override
        public QueryBindings nextBindings() {
             bindings = bindingsCursor.nextBindings();
            return bindings;
        }

        @Override
        public boolean isDestroyed()
        {
            return destroyed;
        }

        @Override
        public void cancelBindings(QueryBindings bindings) {
            bindingsCursor.cancelBindings(bindings);
        }

        // For use by this class

        private Row getOuterRow()
        {
            Row row = bindings.getRow(rowBindingPosition);
            if (LOG_EXECUTION) {
                LOG.debug("hash_join: outer {}", row);
            }
            return row;
        }

        // Execution interface

        Execution(QueryContext context, QueryBindingsCursor bindingsCursor)
        {
            super(context);
            MultipleQueryBindingsCursor multiple = new MultipleQueryBindingsCursor(bindingsCursor);
            this.bindingsCursor = multiple;
        }
        // Cursor interface
        protected ArrayListMultimap<KeyWrapper, Row> hashTable = ArrayListMultimap.create();
        private boolean closed = true;
        private final QueryBindingsCursor bindingsCursor;
        private List<Row> innerRowList;
        private int innerRowListPosition = 0;
        private QueryBindings bindings;
        private boolean destroyed = false;

    }

    public static class KeyWrapper{
        List<ValueSource> values = new ArrayList<>();
        Integer hashKey = 0;

        @Override
        public int hashCode(){
            return hashKey;
        }

        @Override
        public boolean equals(Object x) {
            if (((KeyWrapper)x).values.size() != this.values.size())
                return false;
            for (int i = 0; i < values.size(); i++) {
                if (((KeyWrapper)x).values.get(i).equals(this.values.get(i)))
                    return false;
            }
            return true;
        }

        public KeyWrapper(Row row, int comparisonFields[], List<AkCollator> collators){

            for (int f = 0; f < comparisonFields.length; f++) {
                ValueSource columnValue=row.value(comparisonFields[f]);
                AkCollator collator = (collators != null) ? collators.get(f) : null;
                hashKey = hashKey ^ ValueSources.hash(columnValue, collator);
                values.add(columnValue);
            }
        }
    }
}
