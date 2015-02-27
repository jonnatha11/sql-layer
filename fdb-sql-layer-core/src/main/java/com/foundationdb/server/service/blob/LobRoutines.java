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

package com.foundationdb.server.service.blob;


import com.foundationdb.server.error.LobUnsupportedException;
import com.foundationdb.server.service.ServiceManager;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.service.transaction.TransactionService;
import com.foundationdb.server.store.FDBTransactionService;
import com.foundationdb.sql.server.ServerQueryContext;
import com.foundationdb.sql.server.ServerCallContextStack;
import com.foundationdb.qp.operator.QueryContext;

import java.util.*;

public class LobRoutines {
    
    private LobRoutines() {
    }

    public static String createNewLob() {
        return createNewLob(false, null);
    }
    
    public static String createNewSpecificLob(String lobId) {
        return createNewLob(true, lobId);
    }
    
    private static String createNewLob(boolean specific, String lobId) {
        FDBTransactionService txnService = getTransactionService();
        final ServerQueryContext context = ServerCallContextStack.getCallingContext();
        final Session session = context.getSession();
        boolean startedTransaction = false;
        final UUID id; 
        if (specific) {
            // This is a check for valid format of id 
            id = UUID.fromString(lobId);
        } else {
            id = java.util.UUID.randomUUID();
        }
        
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        try {
            getLobService().createNewLob(session, id);
            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
        return id.toString();
    }
    
    
    public static long sizeBlob(final String blobId) {
        FDBTransactionService txnService = getTransactionService();
        final QueryContext context = ServerCallContextStack.getCallingContext();
        final Session session = context.getSession();
        boolean startedTransaction = false;
        long size; 
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        final UUID id = UUID.fromString(blobId);
        
        try {
            LobService ls = getLobService();
            ls.verifyAccessPermission(session, context, id);
            size = ls.sizeBlob(session, id);
            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
        return size;
    }
    
    public static void readBlob(final long offset, final int length, final String blobId, byte[][] out) {
        FDBTransactionService txnService = getTransactionService();
        final QueryContext context = ServerCallContextStack.getCallingContext();
        final UUID id = UUID.fromString(blobId);
        final Session session = context.getSession();
        boolean startedTransaction = false;
        byte[] output;
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        try {
            LobService ls = getLobService();
            ls.verifyAccessPermission(session, context, id);
            output = ls.readBlob(session, id, offset, length);
            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
        out[0] = output; 
    }
    
    public static void writeBlob(final long offset, final byte[] data, final String blobId){
        FDBTransactionService txnService = getTransactionService();
        final QueryContext context = ServerCallContextStack.getCallingContext();
        final Session session = context.getSession();
        boolean startedTransaction = false;
        final UUID id = UUID.fromString(blobId);
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        try {
            LobService ls = getLobService();
            ls.verifyAccessPermission(session, context, id);
            ls.writeBlob(session, id, offset, data);

            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
    }

    public static void appendBlob(final byte[] data, final String blobId) {
        FDBTransactionService txnService = getTransactionService();
        final QueryContext context = ServerCallContextStack.getCallingContext();
        final Session session = context.getSession();
        boolean startedTransaction = false;
        final UUID id = UUID.fromString(blobId);
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        try {
            LobService ls = getLobService();
            ls.verifyAccessPermission(session, context, id);
            ls.appendBlob(session, id, data);
            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
    }

    public static void truncateBlob(final long newLength, final String blobId) {
        FDBTransactionService txnService = getTransactionService();
        final QueryContext context = ServerCallContextStack.getCallingContext();
        final Session session = context.getSession();
        boolean startedTransaction = false;
        final UUID id = UUID.fromString(blobId);
        if (!txnService.isTransactionActive(session)) {
            txnService.beginTransaction(session);
            startedTransaction = true;
        }
        try {
            LobService ls = getLobService();
            ls.verifyAccessPermission(session, context, id);
            ls.truncateBlob(session, id, newLength);
            if (startedTransaction) {
                txnService.commitTransaction(session);
            }
        } finally {
            if (startedTransaction) {
                txnService.rollbackTransactionIfOpen(session);
            }
        }
    }
    
    private static LobService getLobService() {
        QueryContext context = ServerCallContextStack.getCallingContext();
        ServiceManager serviceManager = context.getServiceManager();
        return serviceManager.getServiceByClass(LobService.class);        
    }

    private static FDBTransactionService getTransactionService() {
        QueryContext context = ServerCallContextStack.getCallingContext();
        ServiceManager serviceManager = context.getServiceManager();
        TransactionService txnService = serviceManager.getServiceByClass(TransactionService.class);
        if (txnService instanceof  FDBTransactionService ) {
            return ((FDBTransactionService) txnService);
        } 
        else 
            throw new LobUnsupportedException("Unsupported transaction service");
    }
}
