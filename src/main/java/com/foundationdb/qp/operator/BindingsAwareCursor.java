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

/**
 * A RowCursor which also support rebinding from the QueryBindings
 * 
 * @see GroupCursor, @see Rebindable
 *
 * Used by
 * @see AncestorLookup_Nested$AncestorCursor
 * @see BranchLookup_Nested$BranchCursor
 * @See GroupScan_Default$HKeyBoundCursor
 * 
 * @see com.foundationdb.qp.util.MultiCursor
 * @See PersistitIndexCursor
 * 
 * @See IndexCursor
 */
public interface BindingsAwareCursor extends RowCursor
{
    public void rebind(QueryBindings bindings);
}
