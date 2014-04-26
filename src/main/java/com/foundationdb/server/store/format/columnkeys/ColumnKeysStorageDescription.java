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

package com.foundationdb.server.store.format.columnkeys;

import com.foundationdb.Range;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.Join;
import com.foundationdb.ais.model.StorageDescription;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.validation.AISValidationFailure;
import com.foundationdb.ais.model.validation.AISValidationOutput;
import com.foundationdb.ais.protobuf.AISProtobuf.Storage;
import com.foundationdb.ais.protobuf.FDBProtobuf;
import com.foundationdb.ais.protobuf.FDBProtobuf.ColumnKeys;
import com.foundationdb.ais.protobuf.FDBProtobuf.TupleUsage;
import com.foundationdb.server.error.AkibanInternalException;
import com.foundationdb.server.error.StorageDescriptionInvalidException;
import com.foundationdb.server.rowdata.FieldDef;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.rowdata.RowDataValueSource;
import com.foundationdb.server.rowdata.RowDef;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.store.FDBStore;
import com.foundationdb.server.store.FDBStoreData;
import com.foundationdb.server.store.FDBTransactionService.TransactionState;
import com.foundationdb.server.store.format.FDBStorageDescription;
import com.foundationdb.server.store.format.tuple.TupleRowDataConverter;
import com.foundationdb.server.store.format.tuple.TupleStorageDescription;
import com.foundationdb.server.types.value.ValueSources;
import com.foundationdb.tuple.ByteArrayUtil;
import com.foundationdb.tuple.Tuple;
import com.persistit.Key;
import com.persistit.KeyShim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.foundationdb.server.store.FDBStoreDataHelper.*;

/**
 * Encode a row as separate key/value pairs, one for each column.
 * The key is the same as for {@link TupleStorageDescription}, plus an
 * element of the column name string.
 * The value is a single <code>Tuple</code> element.
 * Child rows follow immediately after the last parent column, due to
 * the tuple encoding using 02 for column name strings and 0C-1C for
 * orginal integers.
 */
public class ColumnKeysStorageDescription extends FDBStorageDescription
{
    protected static final byte[] FIRST_NUMERIC = { 0x0C };

    public ColumnKeysStorageDescription(HasStorage forObject) {
        super(forObject);
    }

    public ColumnKeysStorageDescription(HasStorage forObject, ColumnKeysStorageDescription other) {
        super(forObject, other);
    }

    @Override
    public StorageDescription cloneForObject(HasStorage forObject) {
        return new ColumnKeysStorageDescription(forObject, this);
    }

    @Override
    public void writeProtobuf(Storage.Builder builder) {
        super.writeProtobuf(builder);
        builder.setExtension(FDBProtobuf.columnKeys, ColumnKeys.YES); // no options yet
        writeUnknownFields(builder);
    }

    @Override
    public void validate(AISValidationOutput output) {
        super.validate(output);
        if (!(object instanceof Group)) {
            output.reportFailure(new AISValidationFailure(new StorageDescriptionInvalidException(object, "is not a Group")));
            return;
        }
        List<String> illegal = TupleRowDataConverter.checkTypes((Group)object, TupleUsage.KEY_AND_ROW);
        if (!illegal.isEmpty()) {
            output.reportFailure(new AISValidationFailure(new StorageDescriptionInvalidException(object, "has some types that cannot be stored in a Tuple: " + illegal)));
        }
    }

    @Override
    public byte[] getKeyBytes(Key key) {
        Key.EdgeValue edge = null;
        int nkeys = key.getDepth();
        if (KeyShim.isBefore(key)) {
            edge = Key.BEFORE;
            nkeys--;
        }
        else if (KeyShim.isAfter(key)) {
            edge = Key.AFTER;
            nkeys--;
        }
        // Get the base key prefix, all but column name.
        Object[] keys = new Object[nkeys];
        key.reset();
        for (int i = 0; i < nkeys; i++) {
            keys[i] = key.decode();
        }
        byte[] bytes = Tuple.from(keys).pack();
        if (edge == Key.BEFORE) {
            // Meaning start with descendants.
            return ByteArrayUtil.join(bytes, FIRST_NUMERIC);
        }
        else if (edge == Key.AFTER) {
            if (nkeys == 0) {
                return new byte[] { (byte)0xFF };
            }
            else {
                return ByteArrayUtil.strinc(bytes);
            }
        }
        else {
            return bytes;
        }
    }

    @Override
    public void getTupleKey(Tuple t, Key key) {
        key.clear();
        TupleStorageDescription.appendHKeySegments(t, key, ((Group)object));
    }

    @Override
    public void packRowData(FDBStore store, Session session,
                            FDBStoreData storeData, RowData rowData) {
        RowDef rowDef = rowDefFromId(((Group)object).getRoot(), rowData.getRowDefId());
        if (rowDef == null) {
            throw new AkibanInternalException("Cannot find table " + rowData);
        }
        RowDataValueSource valueSource = new RowDataValueSource();
        int nfields = rowDef.getFieldCount();
        Map<String,Object> value = new HashMap<>(nfields); // Intermediate form of value.
        for (int i = 0; i < nfields; i++) {
            FieldDef fieldDef = rowDef.getFieldDef(i);
            valueSource.bind(fieldDef, rowData);
            value.put(fieldDef.getName(), ValueSources.toObject(valueSource));
        }
        storeData.otherValue = value;
    }

    private static RowDef rowDefFromId(Table table, int tableId) {
        if (table.getTableId() == tableId) {
            return table.rowDef();
        }
        for (Join join : table.getChildJoins()) {
            RowDef rowDef = rowDefFromId(join.getChild(), tableId);
            if (rowDef != null) {
                return rowDef;
            }
        }
        return null;
    }

    @Override
    public void expandRowData(FDBStore store, Session session,
                              FDBStoreData storeData, RowData rowData) {
        Map<String,Object> value = (Map<String,Object>)storeData.otherValue;
        RowDef rowDef = rowDefFromLastOrdinal(((Group)object).getRoot(), 
                                              storeData.persistitKey);
        assert (rowDef != null) : storeData.persistitKey;
        int nfields = rowDef.getFieldCount();
        Object[] objects = new Object[nfields];
        for (int i = 0; i < nfields; i++) {
            objects[i] = value.get(rowDef.getFieldDef(i).getName());
        }
        if (rowData.getBytes() == null) {
            rowData.reset(new byte[RowData.CREATE_ROW_INITIAL_SIZE]);
        }
        rowData.createRow(rowDef, objects, true);
    }

    private static RowDef rowDefFromLastOrdinal(Table root, Key hkey) {
        hkey.reset();
        int ordinal = hkey.decodeInt();
        assert (root.getOrdinal() == ordinal) : hkey;
        Table table = root;
        int index = 0;
        while (true) {
            index += 1 + table.getPrimaryKeyIncludingInternal().getColumns().size();
            if (index >= hkey.getDepth()) {
                return table.rowDef();
            }
            hkey.indexTo(index);
            ordinal = hkey.decodeInt();
            boolean found = false;
            for (Join join : table.getChildJoins()) {
                table = join.getChild();
                if (table.getOrdinal() == ordinal) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AkibanInternalException("Not a child ordinal " + hkey);
            }
        }
    }

    @Override
    public void store(FDBStore store, Session session, FDBStoreData storeData) {
        TransactionState txn = store.getTransaction(session, storeData);
        // Erase all previous column values, in case not present in Map.
        txn.clearRange(storeData.rawKey, ByteArrayUtil.join(storeData.rawKey, FIRST_NUMERIC));
        Map<String,Object> value = (Map<String,Object>)storeData.otherValue;
        for (Map.Entry<String,Object> entry : value.entrySet()) {
            txn.setBytes(ByteArrayUtil.join(storeData.rawKey,
                                            Tuple.from(entry.getKey()).pack()),
                         Tuple.from(entry.getValue()).pack());
        }
    }

    @Override
    public boolean fetch(FDBStore store, Session session, FDBStoreData storeData) {
        // Cannot get in a single fetch.
        try {
            groupIterator(store, session, storeData,
                          FDBStore.GroupIteratorBoundary.KEY,
                          FDBStore.GroupIteratorBoundary.FIRST_DESCENDANT,
                          1);
            return storeData.next();
        }
        finally {
            storeData.closeIterator();
        }
    }

    @Override
    public void clear(FDBStore store, Session session, FDBStoreData storeData) {
        TransactionState txn = store.getTransaction(session, storeData);
        byte[] begin = storeData.rawKey;
        byte[] end = ByteArrayUtil.join(begin, FIRST_NUMERIC);
        txn.clearRange(begin, end);
    }

    public void groupIterator(FDBStore store, Session session, FDBStoreData storeData,
                              FDBStore.GroupIteratorBoundary left, FDBStore.GroupIteratorBoundary right,
                              int limit) {
        byte[] begin, end;
        switch (left) {
        case START:
            begin = prefixBytes(storeData);
            break;
        case KEY:
            begin = packKey(storeData);
            break;
        case NEXT_KEY:
            // Meaning possibly descendants.
        case FIRST_DESCENDANT:
            begin = ByteArrayUtil.join(packKey(storeData), FIRST_NUMERIC);
            break;
        default:
            throw new IllegalArgumentException(left.toString());
        }
        switch (right) {
        case END:
            end = ByteArrayUtil.strinc(prefixBytes(storeData));
            break;
        case FIRST_DESCENDANT:
            end = ByteArrayUtil.join(packKey(storeData), FIRST_NUMERIC);
            break;
        case LAST_DESCENDANT:
            end = packKey(storeData, Key.AFTER);
            break;
        default:
            throw new IllegalArgumentException(right.toString());
        }
        storeData.iterator = 
            new ColumnKeysStorageIterator(storeData,
                                          store.getTransaction(session, storeData)
                                          .getRangeIterator(begin, end),
                                          limit);
    }

    public void indexIterator(FDBStore store, Session session, FDBStoreData storeData,
                              boolean key, boolean inclusive, boolean reverse) {
        throw new UnsupportedOperationException();
    }

}
