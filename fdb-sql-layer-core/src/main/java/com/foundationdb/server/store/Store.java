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

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.GroupIndex;
import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.Sequence;
import com.foundationdb.ais.model.StorageDescription;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.qp.operator.StoreAdapter;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.row.WriteIndexRow;
import com.foundationdb.qp.storeadapter.indexrow.SpatialColumnHandler;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.service.tree.KeyCreator;
import com.foundationdb.server.store.TableChanges.ChangeSet;
import com.persistit.Key;
import com.persistit.Value;

import java.util.Collection;

public interface Store extends KeyCreator {

    /** Get the RowDef for the given ID. Note, a transaction should be active before calling this. */
    AkibanInformationSchema getAIS(Session session);

    /**  If not {@code null}, only maintain the given {@code tableIndexes} and {@code groupIndexes}. */
    void writeRow(Session session, Row row, Collection<TableIndex> tableIndexes, Collection<GroupIndex> groupIndexes);

    void deleteRow(Session session, Row row, boolean cascadeDelete);

    /** newRow can be partial, as specified by selector, but oldRow must be fully present. */
    void updateRow(Session session, Row oldRow, Row newRow);

    /** Save the TableIndex row for {@code rowData}. {@code hKey} must be populated. */
    void writeIndexRow(Session session, TableIndex index, Row row, Key hKey, WriteIndexRow buffer,
                        SpatialColumnHandler spatialColumnHandler, long zValue, boolean doLock);
    /** Clear the TableIndex row for {@code rowData]. {@code hKey} must be populated. */
    void deleteIndexRow(Session session, TableIndex index, Row row, Key hKey, WriteIndexRow buffer,
            SpatialColumnHandler spatialColumnHandler, long zValue, boolean doLock);

    /** Save the GroupIndex rows for {@code rowData}. Locking handed by StoreGIHandler. */
    void writeIndexRows(Session session, Table table, Row row, Collection<GroupIndex> indexes);

    /** Clear the GroupIndex rows for {@code rowData}. Locking handled by StoreGIHandler. */
    void deleteIndexRows(Session session, Table table, Row row, Collection<GroupIndex> indexes);

    /** Compute and return the next value for the given sequence */
    long nextSequenceValue(Session session, Sequence sequence);

    /**
     * Retrieve the current value for the given sequence.
     * <p><i>Note: In general, the next value has no relationship to a given transaction's current.</i></p>
     */
    long curSequenceValue(Session session, Sequence sequence);

    /**
     * Delete all data associated with the group. This includes
     * all indexes from all tables, group indexes, and the group itself.
     */
    void dropGroup(Session session, Group group);


    /**
     * Delete all data associated with the schema.
     */
    void dropSchema(Session session, com.foundationdb.ais.model.Schema schema);

    /** (Test helper) Drop all non-system schemas
     * @param allSchemas A list of all the schemas in the system, can include system schemas
     */
    void dropNonSystemSchemas(Session session, Collection<com.foundationdb.ais.model.Schema> allSchemas);

    /**
     * Truncate the given group. This includes indexes from all tables, group
     * indexes, the group itself, and all table statuses.
     */
    void truncateGroup(Session session, Group group);

    void truncateTableStatus(Session session, int rowDefId);

    void deleteIndexes(Session session, Collection<? extends Index> indexes);

    void deleteSequences (Session session, Collection<? extends Sequence> sequences);
    /**
     * Remove all trees, and their contents, associated with the given table.
     * @param session Session
     * @param table Table
     * @throws Exception
     */
    void removeTrees(Session session, Table table);

     /**
     * Remove all trees, and their contents, associated with the given schema.
     */
    void removeTrees(Session session, com.foundationdb.ais.model.Schema schema);
    void removeTree(Session session, HasStorage object);
    void truncateTree(Session session, HasStorage object);
    void dropAllLobs(Session session);
    
    /**
     * Low level operation. Removes the given trees and <i>only</i> the given trees.
     * To ensure metadata and other state is updated, check if another method for
     * specific entities is more appropriate (e.g. {@link #deleteIndexes(Session, Collection)}).
     */
    void removeTrees(Session session, Collection<? extends HasStorage> objects);

    void truncateIndexes(Session session, Collection<? extends Index> indexes);

    StoreAdapter createAdapter(Session session);

    boolean treeExists(Session session, StorageDescription storageDescription);

    // TODO: Better abstraction
    void traverse(Session session, Group group, TreeRecordVisitor visitor);
    <V extends IndexVisitor<Key,Value>> V traverse(Session session, Index index, V visitor, long scanTimeLimit, long sleepTime);

    /** Clear any storage affected by the online change. */
    void discardOnlineChange(Session session, Collection<ChangeSet> changeSets);

    /** Update any storage affected by a successful online change. */
    void finishOnlineChange(Session session, Collection<ChangeSet> changeSets);

    /**
     * return name of this store, for display to the user
     * @return name
     */
    String getName();

    /** (Test helper) Get names of all StorageDescriptions in use. */
    Collection<String> getStorageDescriptionNames(Session session);

    OnlineHelper getOnlineHelper();

    /** (Test helper) Get exception thrown for online DML vs DDL violation */
    Class<? extends Exception> getOnlineDMLFailureException();

    /** (Test helper) Can be restarted and keep data. */
    boolean isRestartable();
}
