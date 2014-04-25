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

package com.foundationdb.server.store;

import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.StorageDescription;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.service.session.Session;

/** Storage associated with a <code>Store</code>. 
*/
public abstract class StoreStorageDescription<SType,SDType> extends StorageDescription
{
    public StoreStorageDescription(HasStorage forObject) {
        super(forObject);
    }

    public StoreStorageDescription(HasStorage forObject, StoreStorageDescription<SType,SDType> other) {
        super(forObject);
    }

    /** Fill the given <code>RowData</code> from the current value. */
    public abstract void expandRowData(SType store, Session session, 
                                       SDType storeData, RowData rowData);

    /** Store the RowData in associated value. */
    public abstract void packRowData(SType store, Session session, 
                                     SDType storeData, RowData rowData);
}
