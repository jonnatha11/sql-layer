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
import com.foundationdb.server.api.dml.ColumnSelector;

/**
 * Abstract implementation of RowCursor. 
 * 
 *  Implements the state checking for the CursorBase, but not any of the
 *  operations methods. If you change this, also change @See OperatorExecutionBase
 *  as the two set of state implementations should match. 
 *
 * @see AncestorLookup_Nested$AncestorCursor
 * @see BranchLookup_Nested$BranchCursor
 * @see GroupScan_Default$HKeyBoundCursor
 * 
 *
 * @see SorterToCursorAdapter
 * @see com.foundationdb.qp.storeadapter.indexcursor.MergeJoinSorter$KeyFinalCursor
 * @see com.foundationdb.qp.storeadapter.PersistitIndexCursor
 * @see com.foundationdb.server.service.text.FullTextCursor
 * @see com.foundationdb.sql.embedded.ExecutableModifyOperatorStatement$SpoolCursor
 * 
 * @see com.foundationdb.qp.storeadapter.indexcursor.IndexCursor
 *  * @see com.foundationdb.qp.storeadapter.indexcursor.IndexCursorMixedOrder
 *  * @see com.foundationdb.qp.storeadapter.indexcursor.IndexCursorSpatial_InBox
 *  * @see com.foundationdb.qp.storeadapter.indexcursor.IndexCursorSpatial_NearPoint
 *  * @see com.foundationdb.qp.storeadapter.indexcursor.IndexCursorUnidrectional
 *  
 * @see com.foundationdb.qp.memoryadapter.MemoryGroupCursor
 * @see com.foundationdb.qp.storeadapter.FDBGroupCursor
 * @see com.foundationdb.qp.storeadapter.PersistitGroupCursor
 */
public abstract class RowCursorImpl implements RowCursor {

    @Override
    public void open() {
        CursorLifecycle.checkClosed(this);
        state = CursorLifecycle.CursorState.ACTIVE;
    }
    
    @Override 
    public void close() {
        if (ExecutionBase.CURSOR_LIFECYCLE_ENABLED) {
            CursorLifecycle.checkIdleOrActive(this);
        }
        state = CursorLifecycle.CursorState.CLOSED;
    }
    
    @Override
    public void setIdle() {
        state = CursorLifecycle.CursorState.IDLE;
    }

    @Override
    public boolean isIdle()
    {
        return state == CursorLifecycle.CursorState.IDLE;
    }

    @Override
    public boolean isActive()
    {
        return state == CursorLifecycle.CursorState.ACTIVE;
    }

    @Override
    public boolean isClosed()
    {
        return state == CursorLifecycle.CursorState.CLOSED;
    }

    @Override
    public void jump(Row row, ColumnSelector columnSelector) {
        throw new UnsupportedOperationException();            
    }

    // Object State
    protected CursorLifecycle.CursorState state = CursorLifecycle.CursorState.CLOSED;
}
