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

package com.foundationdb.sql.server;

import com.foundationdb.ais.model.ForeignKey;
import com.foundationdb.ais.model.Table;
import com.foundationdb.qp.memoryadapter.MemoryAdapter;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.operator.StoreAdapter;
import com.foundationdb.server.error.AkibanInternalException;
import com.foundationdb.server.error.ImplicitlyCommittedException;
import com.foundationdb.server.error.InvalidOperationException;
import com.foundationdb.server.error.InvalidParameterValueException;
import com.foundationdb.server.error.NoTransactionInProgressException;
import com.foundationdb.server.error.TransactionAbortedException;
import com.foundationdb.server.error.TransactionInProgressException;
import com.foundationdb.server.error.TransactionReadOnlyException;
import com.foundationdb.server.types.FormatOptionImpl;
import com.foundationdb.server.types.service.TypesRegistryService;
import com.foundationdb.server.service.ServiceManager;
import com.foundationdb.server.service.dxl.DXLService;
import com.foundationdb.server.service.externaldata.ExternalDataService;
import com.foundationdb.server.service.monitor.SessionMonitor;
import com.foundationdb.server.service.routines.RoutineLoader;
import com.foundationdb.server.service.security.SecurityService;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.service.transaction.TransactionService;
import com.foundationdb.server.service.tree.KeyCreator;
import com.foundationdb.server.types.common.types.TypesTranslator;
import com.foundationdb.sql.optimizer.AISBinderContext;
import com.foundationdb.sql.optimizer.rule.PipelineConfiguration;
import com.foundationdb.sql.optimizer.rule.cost.CostEstimator;

import java.io.IOException;
import java.util.*;

public abstract class ServerSessionBase extends AISBinderContext implements ServerSession
{
    public static final String COMPILER_PROPERTIES_PREFIX = "optimizer.";
    public static final String PIPELINE_PROPERTIES_PREFIX = "fdbsql.pipeline.";
    public static final String FEATURE_DIRECT_ROUTINES_PROP = "fdbsql.feature.direct_routines_on";

    protected final ServerServiceRequirements reqs;
    protected Properties compilerProperties;
    protected Map<String,Object> attributes = new HashMap<>();
    protected PipelineConfiguration pipelineConfiguration;
    protected Boolean directEnabled;
    
    protected Session session;
    protected Map<StoreAdapter.AdapterType, StoreAdapter> adapters = 
        new HashMap<>();
    protected ServerTransaction transaction;
    protected boolean transactionDefaultReadOnly = false;
    protected boolean transactionPeriodicallyCommit = false;
    protected ServerSessionMonitor sessionMonitor;

    protected Long queryTimeoutMilli = null;
    protected ServerValueEncoder.ZeroDateTimeBehavior zeroDateTimeBehavior = ServerValueEncoder.ZeroDateTimeBehavior.NONE;
    protected FormatOptionImpl.FormatOptions options = new FormatOptionImpl.FormatOptions();    
    protected QueryContext.NotificationLevel maxNotificationLevel = QueryContext.NotificationLevel.INFO;

    public ServerSessionBase(ServerServiceRequirements reqs) {
        this.reqs = reqs;
    }

    @Override
    public void setProperty(String key, String value) {
        String ovalue = (String)properties.get(key); // Not inheriting.
        super.setProperty(key, value);
        try {
            if (!propertySet(key, properties.getProperty(key)))
                sessionChanged();   // Give individual handlers a chance.
        }
        catch (InvalidOperationException ex) {
            super.setProperty(key, ovalue);
            try {
                if (!propertySet(key, properties.getProperty(key)))
                    sessionChanged();
            }
            catch (InvalidOperationException ex2) {
                throw new AkibanInternalException("Error recovering " + key + " setting",
                                                  ex2);
            }
            throw ex;
        }
    }

    protected void setProperties(Properties properties) {
        super.setProperties(properties);
        for (String key : properties.stringPropertyNames()) {
            propertySet(key, properties.getProperty(key));
        }
        sessionChanged();
    }

    /** React to a property change.
     * Implementers are not required to remember the old state on
     * error, but must not leave things in such a mess that reverting
     * to the old value will not work.
     * @see InvalidParameterValueException
     **/
    protected boolean propertySet(String key, String value) {
        if ("zeroDateTimeBehavior".equals(key)) {
            zeroDateTimeBehavior = ServerValueEncoder.ZeroDateTimeBehavior.fromProperty(value);
            return true;
        }
        if (("binary_output").equals(key)){
            FormatOptionImpl.BinaryFormatOption bfo = FormatOptionImpl.BinaryFormatOption.fromProperty(value);
            options.set(bfo);
            return true;
        }
        if  (("jsonbinary_output").equals(key)) {
            FormatOptionImpl.JsonBinaryFormatOption bfo = FormatOptionImpl.JsonBinaryFormatOption.fromProperty(value);
            options.set(bfo);
            return true;            
        }
        if ("maxNotificationLevel".equals(key)) {
            maxNotificationLevel = (value == null) ? 
                QueryContext.NotificationLevel.INFO :
                QueryContext.NotificationLevel.valueOf(value);
            return true;
        }
        if ("queryTimeoutSec".equals(key)) {
            if (value == null)
                queryTimeoutMilli = null;
            else
                queryTimeoutMilli = (long)(Double.parseDouble(value) * 1000);
            return true;
        }
        if ("transactionPeriodicallyCommit".equals(key)) {
            boolean periodicallyCommit = (value != null) && Boolean.parseBoolean(value);
            transactionPeriodicallyCommit = periodicallyCommit;
            if (transaction != null)
                transaction.setPeriodicallyCommit(periodicallyCommit);
            return true;
        }
        if ("constraintCheckTime".equals(key)) {
            reqs.txnService().setSessionOption(session, 
                                               TransactionService.SessionOption.CONSTRAINT_CHECK_TIME, 
                                               value);
            return true;
        }
        return false;
    }

    @Override
    public void setDefaultSchemaName(String defaultSchemaName) {
        super.setDefaultSchemaName(defaultSchemaName);
        sessionChanged();
    }

    @Override
    public String getSessionSetting(String key) {
        return getProperty(key);
    }

    protected abstract void sessionChanged();

    @Override
    public Map<String,Object> getAttributes() {
        return attributes;
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object attr) {
        attributes.put(key, attr);
        sessionChanged();
    }

    @Override
    public DXLService getDXL() {
        return reqs.dxl();
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public AISBinderContext getBinderContext() {
        return this;
    }

    @Override
    public Properties getCompilerProperties() {
        if (compilerProperties == null)
            compilerProperties = reqs.config().deriveProperties(COMPILER_PROPERTIES_PREFIX);
        return compilerProperties;
    }

    @Override
    public SessionMonitor getSessionMonitor() {
        return sessionMonitor;
     }

    @Override
    public StoreAdapter getStore() {
        return adapters.get(StoreAdapter.AdapterType.STORE_ADAPTER);
    }
    
    @Override
    public StoreAdapter getStore(Table table) {
        if (table.hasMemoryTableFactory()) {
            return adapters.get(StoreAdapter.AdapterType.MEMORY_ADAPTER);
        }
        return adapters.get(StoreAdapter.AdapterType.STORE_ADAPTER);
    }

    @Override
    public TransactionService getTransactionService() {
        return reqs.txnService();
    }

    @Override
    public boolean isTransactionActive() {
        return (transaction != null);
    }

    @Override
    public boolean isTransactionRollbackPending() {
        return ((transaction != null) && transaction.isRollbackPending());
    }

    @Override
    public void beginTransaction() {
        if (transaction != null)
            throw new TransactionInProgressException();
        transaction = new ServerTransaction(this, transactionDefaultReadOnly, transactionPeriodicallyCommit);
    }

    @Override
    public void commitTransaction() {
        if (transaction == null) {
            warnClient(new NoTransactionInProgressException());
            return;
        }
        try {
            transaction.commit();
        }
        finally {
            transaction = null;
        }
    }

    @Override
    public void rollbackTransaction() {
        if (transaction == null) {
            warnClient(new NoTransactionInProgressException());
            return;
        }
        try {
            transaction.rollback();
        }
        finally {
            transaction = null;
        }
    }

    @Override
    public void setTransactionReadOnly(boolean readOnly) {
        if (transaction == null)
            throw new NoTransactionInProgressException();
        transaction.setReadOnly(readOnly);
    }

    @Override
    public void setTransactionDefaultReadOnly(boolean readOnly) {
        this.transactionDefaultReadOnly = readOnly;
    }

    @Override
    public boolean isTransactionPeriodicallyCommit() {
        return transactionPeriodicallyCommit;
    }

    @Override
    public void setTransactionPeriodicallyCommit(boolean periodicallyCommit) {
        this.transactionPeriodicallyCommit = periodicallyCommit;
    }

    @Override
    public TypesRegistryService typesRegistryService() {
        return reqs.typesRegistryService();
    }

    @Override
    public TypesTranslator typesTranslator() {
        return reqs.dxl().ddlFunctions().getTypesTranslator();
    }

    @Override
    public RoutineLoader getRoutineLoader() {
        return reqs.routineLoader();
    }

    @Override
    public ExternalDataService getExternalDataService() {
        return reqs.externalData();
    }

    @Override
    public SecurityService getSecurityService() {
        return reqs.securityService();
    }

    @Override
    public ServiceManager getServiceManager() {
        return reqs.serviceManager();
    }

    @Override
    public Date currentTime() {
        return new Date();
    }

    @Override
    public long getQueryTimeoutMilli() {
        if (queryTimeoutMilli != null)
            return queryTimeoutMilli;
        else
            return reqs.config().queryTimeoutMilli();
    }

    @Override
    public ServerValueEncoder.ZeroDateTimeBehavior getZeroDateTimeBehavior() {
        return zeroDateTimeBehavior;
    }

    @Override
    public FormatOptionImpl.FormatOptions getFormatOptions() {
        return options;
    }
    
    @Override
    public CostEstimator costEstimator(ServerOperatorCompiler compiler, KeyCreator keyCreator) {
        return new ServerCostEstimator(this, reqs, compiler, keyCreator);
    }

    protected void initAdapters(ServerOperatorCompiler compiler) {
        // Add the Store Adapter - default for most tables
        adapters.put(StoreAdapter.AdapterType.STORE_ADAPTER,
                     reqs.store().createAdapter(session, compiler.getSchema()));
        // Add the Memory Adapter - for the in memory tables
        adapters.put(StoreAdapter.AdapterType.MEMORY_ADAPTER, 
                     new MemoryAdapter(compiler.getSchema(),
                                       session,
                                       reqs.config()));
    }

    /** Prepare to execute given statement.
     * Uses current global transaction or makes a new local one.
     * Returns any local transaction that should be committed / rolled back immediately.
     */
    protected boolean beforeExecute(ServerStatement stmt) {
        ServerStatement.TransactionMode transactionMode = stmt.getTransactionMode();
        boolean localTransaction = false;
        if (transaction != null) {
            if(transactionMode == ServerStatement.TransactionMode.IMPLICIT_COMMIT) {
                warnClient(new ImplicitlyCommittedException());
                commitTransaction();
            } else {
                // Use global transaction.
                transaction.checkTransactionMode(transactionMode);
            }
        }
        else {
            switch (transactionMode) {
            case REQUIRED:
            case REQUIRED_WRITE:
                throw new NoTransactionInProgressException();
            case READ:
            case NEW:
                transaction = new ServerTransaction(this, true, false);
                localTransaction = true;
                break;
            case WRITE:
            case NEW_WRITE:
                if (transactionDefaultReadOnly)
                    throw new TransactionReadOnlyException();
                transaction = new ServerTransaction(this, false, false);
                transaction.beforeUpdate();
                localTransaction = true;
                break;
            }
        }
        if (isTransactionRollbackPending()) {
            ServerStatement.TransactionAbortedMode abortedMode = stmt.getTransactionAbortedMode();
            switch (abortedMode) {
                case ALLOWED:
                    break;
                case NOT_ALLOWED:
                    throw new TransactionAbortedException();
                default:
                    throw new IllegalStateException("Unknown mode: " + abortedMode);
            }
        }
        return localTransaction;
    }

    /** Complete execute given statement.
     * @see #beforeExecute
     */
    protected void afterExecute(ServerStatement stmt, 
                                boolean localTransaction,
                                boolean success) {
        if (localTransaction) {
            if (success)
                commitTransaction();
            else
                try {
                    transaction.abort();
                }
                finally {
                    transaction = null;
                }
        }
        else if (transaction != null) {
            // Make changes visible in open global transaction.
            ServerStatement.TransactionMode transactionMode = stmt.getTransactionMode();
            switch (transactionMode) {
            case REQUIRED_WRITE:
            case WRITE:
                transaction.afterUpdate();
                break;
            }
            // Give periodic commit a chance if enabled.
            transaction.checkPeriodicallyCommit();
        }
    }

    /** Should be called when embedded connection is opened, possibly
     * within a routine call. */
    protected void inheritFromCall() {
        ServerCallContextStack stack = ServerCallContextStack.get();
        stack.addCallee(this);
        ServerCallContextStack.Entry call = stack.current();
        if (call != null) {
            ServerSessionBase server = (ServerSessionBase)call.getContext().getServer();
            defaultSchemaName = server.defaultSchemaName;
            session = server.session;
            transaction = server.transaction;
            transactionDefaultReadOnly = server.transactionDefaultReadOnly;
            sessionMonitor.setCallerSessionId(server.getSessionMonitor().getSessionId());
        }
        if (transaction == null) {
            transaction = stack.getSharedTransaction();
        }
    }

    /** Called when routine exits to give embedded connection a chance
     * to clean up and report leaks.
     * @param topLevel <code>true</code> if this was the last call, which should force cleanup.
     * @param success <code>false</code> is cleaning up due to error.
     * @return <code>true</code> if needs to be kept open for
     * outstanding <code>ResultSet</code>s.
     */
    public boolean endCall(ServerQueryContext context, 
                           ServerRoutineInvocation invocation,
                           boolean topLevel, boolean success) {
        return false;
    }

    public boolean shouldNotify(QueryContext.NotificationLevel level) {
        return (level.ordinal() <= maxNotificationLevel.ordinal());
    }

    @Override
    public void warnClient(InvalidOperationException e) {
        try {
            notifyClient(QueryContext.NotificationLevel.WARNING, e.getCode(), e.getShortMessage());
        } catch(IOException ioe) {
            // Ignore
        }
    }

    @Override
    public boolean isSchemaAccessible(String schemaName) {
        return reqs.securityService().isAccessible(session, schemaName);
    }

    @Override
    public PipelineConfiguration getPipelineConfiguration() {
        if (pipelineConfiguration == null)
            pipelineConfiguration = new PipelineConfiguration(reqs.config().deriveProperties(PIPELINE_PROPERTIES_PREFIX));
        return pipelineConfiguration;
    }

    @Override
    public boolean isDirectEnabled() {
        if (directEnabled == null)
            directEnabled = Boolean.valueOf(reqs.config().getProperty(FEATURE_DIRECT_ROUTINES_PROP));
        return directEnabled;
    }

    @Override
    public void setDeferredForeignKey(ForeignKey foreignKey, boolean deferred) {
        reqs.txnService().setDeferredForeignKey(session, foreignKey, deferred);
    }
}
