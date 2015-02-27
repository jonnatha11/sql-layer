/**
 * Copyright (C) 2009-2015 FoundationDB, LLC
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

package com.foundationdb.server.types.common.funcs;


import com.foundationdb.server.service.blob.BlobRef;
import com.foundationdb.server.store.FDBStore;
import com.foundationdb.server.types.TScalar;
import com.foundationdb.server.types.TClass;
import com.foundationdb.server.types.TExecutionContext;
import com.foundationdb.server.types.LazyList;
import com.foundationdb.server.types.aksql.aktypes.AkBlob;
import com.foundationdb.server.types.aksql.aktypes.AkGUID;
import com.foundationdb.server.types.texpressions.TScalarBase;
import com.foundationdb.server.types.texpressions.TInputSetBuilder;
import com.foundationdb.server.types.TOverloadResult;
import com.foundationdb.server.types.value.ValueSource;
import com.foundationdb.server.types.value.ValueTarget;

import java.util.UUID;

public class CreateLongBlob extends TScalarBase {
    
    public static TScalar createEmptyLongBlob() {
        return new CreateLongBlob();
    }
    
    public static TScalar createLongBlob(final TClass binaryType) {
        return new CreateLongBlob() {
            @Override
            protected  void buildInputSets(TInputSetBuilder builder) {
                builder.covers(binaryType, 0);
            }
        };
    }
    
    
    private CreateLongBlob() {}

    @Override
    protected void buildInputSets(TInputSetBuilder builder) {
        // does nothing
    }

    @Override
    protected void doEvaluate(TExecutionContext context, LazyList<? extends ValueSource> inputs, ValueTarget output) {
        byte[] data = new byte[0];
        if (inputs.size() == 1) {
            data = inputs.get(0).getBytes();
        }
        String mode = context.getQueryContext().getStore().getConfig().getProperty(AkBlob.RETURN_UNWRAPPED);
        BlobRef.LeadingBitState state = BlobRef.LeadingBitState.NO;
        if (mode.equalsIgnoreCase(AkBlob.WRAPPED)) {
            state = BlobRef.LeadingBitState.YES;
            UUID id = FDBStore.writeDataToNewBlob(context.getQueryContext().getSession(), data);
            byte[] tmp = new byte[17];
            tmp[0] = BlobRef.LONG_LOB;
            System.arraycopy(AkGUID.uuidToBytes(id), 0, tmp, 1, 16);
            data = tmp;
        }

        BlobRef blob = new BlobRef(data, state, BlobRef.LobType.UNKNOWN, BlobRef.LobType.LONG_LOB);
        output.putObject(blob);
    }

    @Override
    public String displayName() {
        return "CREATE_LONG_BLOB";
    }

    @Override
    public TOverloadResult resultType() {
        return TOverloadResult.fixed(AkBlob.INSTANCE);
    }
}

