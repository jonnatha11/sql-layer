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

package com.foundationdb.server.store.statistics;

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexName;
import com.foundationdb.ais.model.IndexRowComposition;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.ais.model.aisb2.AISBBasedBuilder;
import com.foundationdb.ais.model.aisb2.NewAISBuilder;
import com.foundationdb.qp.memoryadapter.MemoryAdapter;
import com.foundationdb.server.TableStatistics;
import com.foundationdb.server.TableStatus;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.rowdata.RowDef;
import com.foundationdb.server.service.Service;
import com.foundationdb.server.service.config.ConfigurationService;
import com.foundationdb.server.service.jmx.JmxManageable;
import com.foundationdb.server.service.listener.ListenerService;
import com.foundationdb.server.service.listener.TableListener;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.server.service.session.SessionService;
import com.foundationdb.server.service.transaction.TransactionService;
import com.foundationdb.server.store.SchemaManager;
import com.foundationdb.server.store.Store;
import com.persistit.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

public abstract class AbstractIndexStatisticsService implements IndexStatisticsService, Service, JmxManageable, TableListener
{
    private static final Logger log = LoggerFactory.getLogger(AbstractIndexStatisticsService.class);

    private static final int INDEX_STATISTICS_TABLE_VERSION = 1;
    private static final String BUCKET_COUNT_PROPERTY = "fdbsql.index_statistics.bucket_count";
    private static final String BUCKET_TIME_PROPERTY = "fdbsql.index_statistics.time_limit";
    private static final String BACKGROUND_TIME_PROPERTY = "fdbsql.index_statistics.background";
    private static final long TIME_LIMIT_UNLIMITED = -1;
    private static final long TIME_LIMIT_DISABLED = -2;

    protected final Store store;
    protected final TransactionService txnService;
    // Following couple only used by JMX method, where there is no context.
    protected final SchemaManager schemaManager;
    protected final SessionService sessionService;
    protected final ConfigurationService configurationService;
    protected final ListenerService listenerService;

    private AbstractStoreIndexStatistics storeStats;
    private Map<Index,IndexStatistics> cache;
    private BackgroundState backgroundState;
    private int bucketCount;
    private long scanTimeLimit, sleepTime, backgroundTimeLimit, backgroundSleepTime;

    protected AbstractIndexStatisticsService(Store store,
                                             TransactionService txnService,
                                             SchemaManager schemaManager,
                                             SessionService sessionService,
                                             ConfigurationService configurationService,
                                             ListenerService listenerService) {
        this.store = store;
        this.txnService = txnService;
        this.schemaManager = schemaManager;
        this.sessionService = sessionService;
        this.configurationService = configurationService;
        this.listenerService = listenerService;
    }

    protected abstract AbstractStoreIndexStatistics createStoreIndexStatistics();


    //
    // Service
    //

    @Override
    public void start() {
        cache = Collections.synchronizedMap(new WeakHashMap<Index,IndexStatistics>());
        storeStats = createStoreIndexStatistics();
        bucketCount = Integer.parseInt(configurationService.getProperty(BUCKET_COUNT_PROPERTY));
        parseTimeLimit(BUCKET_TIME_PROPERTY, false);
        parseTimeLimit(BACKGROUND_TIME_PROPERTY, true);
        registerStatsTables();
        listenerService.registerTableListener(this);
        backgroundState = new BackgroundState(backgroundTimeLimit != TIME_LIMIT_DISABLED);
    }

    private void parseTimeLimit(String key, boolean background) {
        String time = configurationService.getProperty(key);
        long on, sleep;
        if ("disabled".equals(time)) {
            on = TIME_LIMIT_DISABLED;
            sleep = 0;
        }
        else if ("unlimited".equals(time)) {
            on = TIME_LIMIT_UNLIMITED;
            sleep = 0;
        }
        else {
            int idx = time.indexOf(',');
            if (idx < 0) {
                on = Long.parseLong(time);
                sleep = 0;
            }
            else {
                on = Long.parseLong(time.substring(0, idx));
                sleep = Long.parseLong(time.substring(idx+1));
            }
        }
        if (background) {
            backgroundTimeLimit = on;
            backgroundSleepTime = sleep;
        }
        else {
            scanTimeLimit = on;
            sleepTime = sleep;
        }
    }

    @Override
    public void stop() {
        listenerService.deregisterTableListener(this);
        cache = null;
        storeStats = null;
        bucketCount = 0;
        backgroundState.stop();
    }

    @Override
    public void crash() {
        stop();
    }


    //
    // IndexStatisticsService
    //

    @Override
    public IndexStatistics getIndexStatistics(Session session, Index index) {
        // TODO: Use getAnalysisTimestamp() of -1 to mark an "empty"
        // analysis to save going to disk for the same index every
        // time. Should this be a part of the IndexStatistics contract
        // somehow?
        IndexStatistics result = cache.get(index);
        if (result != null) {
            if (result.isInvalid())
                return null;
            else
                return result;
        }
        result = storeStats.loadIndexStatistics(session, index);
        if (result != null) {
            cache.put(index, result);
            return result;
        }
        result = new IndexStatistics(index);
        result.setValidity(IndexStatistics.Validity.INVALID);
        cache.put(index, result);
        return null;
    }

    @Override
    public TableStatistics getTableStatistics(Session session, Table table) {
        final RowDef rowDef = table.rowDef();
        final TableStatistics ts = new TableStatistics(table.getTableId());
        final TableStatus status = rowDef.getTableStatus();
        ts.setAutoIncrementValue(status.getAutoIncrement(session));
        ts.setRowCount(status.getRowCount(session));
        // TODO - get correct values
        ts.setMeanRecordLength(100);
        ts.setBlockSize(8192);
        for(Index index : rowDef.getIndexes()) {
            if(index.isSpatial()) {
                continue;
            }
            TableStatistics.Histogram histogram = indexStatisticsToHistogram(session, index, store.createKey());
            if(histogram != null) {
                ts.addHistogram(histogram);
            }
        }
        return ts;
    }

    @Override
    public void updateIndexStatistics(Session session, 
                                      Collection<? extends Index> indexes) {
        final Map<Index,IndexStatistics> updates = new HashMap<>(indexes.size());
        if (indexes.size() > 0) {
            updates.putAll(updateIndexStatistics(session, indexes, false));
        }
        txnService.addCallback(session, TransactionService.CallbackType.COMMIT, new TransactionService.Callback() {
            @Override
            public void run(Session session, long timestamp) {
                cache.putAll(updates);
                backgroundState.removeAll(updates);
            }
        });
    }

    protected Map<Index,IndexStatistics> updateIndexStatistics(Session session,
                                                               Collection<? extends Index> indexes,
                                                               boolean background) {
        final Index first = indexes.iterator().next();
        final Table table =  first.rootMostTable();
        if (table.hasMemoryTableFactory()) {
            return updateMemoryTableIndexStatistics(session, indexes, background);
        } else {
            return updateStoredTableIndexStatistics(session, indexes, background);
        }
    }

    private Map<Index,IndexStatistics> updateStoredTableIndexStatistics(Session session,
                                                                        Collection<? extends Index> indexes,
                                                                        boolean background) {
        Map<Index,IndexStatistics> updates = new HashMap<>(indexes.size());
        long on, sleep;
        if (background) {
            on = backgroundTimeLimit;
            sleep = backgroundSleepTime;
        }
        else {
            on = scanTimeLimit;
            sleep = sleepTime;
        }
        for (Index index : indexes) {
            IndexStatistics indexStatistics = storeStats.computeIndexStatistics(session, index, on, sleep);
            storeStats.storeIndexStatistics(session, index, indexStatistics);
            updates.put(index, indexStatistics);
        }
        return updates;
    }
    
    private Map<Index,IndexStatistics> updateMemoryTableIndexStatistics (Session session, Collection<? extends Index> indexes, boolean background) {
        Map<Index,IndexStatistics> updates = new HashMap<>(indexes.size());
        IndexStatistics indexStatistics;
        for (Index index : indexes) {
            // memory store, when it calculates index statistics, and supports group indexes
            // will work on the root table. 
            final Table table =  index.rootMostTable();
            indexStatistics = MemoryAdapter.getMemoryTableFactory(table).computeIndexStatistics(session, index);

            if (indexStatistics != null) {
                updates.put(index, indexStatistics);
            }
        }
        return updates;
    }

    @Override
    public void deleteIndexStatistics(Session session, 
                                      Collection<? extends Index> indexes) {
        for(Index index : indexes) {
            storeStats.removeStatistics(session, index);
            cache.remove(index);
        }
    }

    @Override
    public void loadIndexStatistics(Session session, 
                                    String schema, File file) throws IOException {
        AkibanInformationSchema ais = schemaManager.getAis(session);
        Map<Index,IndexStatistics> stats = new IndexStatisticsYamlLoader(ais, schema, store).load(file);
        for (Map.Entry<Index,IndexStatistics> entry : stats.entrySet()) {
            Index index = entry.getKey();
            IndexStatistics indexStatistics = entry.getValue();
            storeStats.storeIndexStatistics(session, index, indexStatistics);
            cache.put(index, indexStatistics);
            backgroundState.remove(index);
        }
    }

    @Override
    public void dumpIndexStatistics(Session session, 
                                    String schema, Writer file) throws IOException {
        List<Index> indexes = new ArrayList<>();
        Set<Group> groups = new HashSet<>();
        AkibanInformationSchema ais = schemaManager.getAis(session);
        for (Table table : ais.getTables().values()) {
            if (table.getName().getSchemaName().equals(schema)) {
                indexes.addAll(table.getIndexes());
                if (groups.add(table.getGroup()))
                    indexes.addAll(table.getGroup().getIndexes());
            }
        }
        // Get all the stats already computed for an index on this schema.
        Map<Index,IndexStatistics> toDump = new TreeMap<>(IndexStatisticsYamlLoader.INDEX_NAME_COMPARATOR);
        for (Index index : indexes) {
            IndexStatistics stats = getIndexStatistics(session, index);
            if (stats != null) {
                toDump.put(index, stats);
            }
        }
        new IndexStatisticsYamlLoader(ais, schema, store).dump(toDump, file);
    }

    @Override
    public void clearCache() {
        cache.clear();
    }

    @Override
    public int bucketCount() {
        return bucketCount;
    }

    @Override
    public void missingStats(Session session, Index index, Column column) {
        if (index == null) {
            log.warn("No statistics for {}.{}; cost estimates will not be accurate", column.getTable().getName(), column.getName());
        }
        else {
            IndexStatistics stats = cache.get(index);
            if ((stats != null) && stats.isInvalid() && !stats.isWarned()) {
                if (index.isTableIndex()) {
                    Table table = ((TableIndex)index).getTable();
                    log.warn("No statistics for table {}; cost estimates will not be accurate", table.getName());
                    stats.setWarned(true);
                    backgroundState.offer(table);
                }
                else {
                    log.warn("No statistics for index {}; cost estimates will not be accurate", index.getIndexName());
                    stats.setWarned(true);
                    backgroundState.offer(index);
                }
            }
        }
    }

    public static final double MIN_ROW_COUNT_SMALLER = 0.2;
    public static final double MAX_ROW_COUNT_LARGER = 5.0;

    @Override
    public void checkRowCountChanged(Session session, Table table,
                                     IndexStatistics stats, long rowCount) {
        if (stats.isValid() && !stats.isWarned()) {
            double ratio = (double)Math.max(rowCount, 1) /
                           (double)Math.max(stats.getRowCount(), 1);
            String msg = null;
            long change = 1;
            if (ratio < MIN_ROW_COUNT_SMALLER) {
                msg = "smaller";
                change = Math.round(1.0 / ratio);
            }
            else if (ratio > MAX_ROW_COUNT_LARGER) {
                msg = "larger";
                change = Math.round(ratio);
            }
            if (msg != null) {
                stats.setValidity(IndexStatistics.Validity.OUTDATED);
                log.warn("Table {} is {} times {} than on {}; cost estimates will not be accurate until statistics are updated", new Object[] { table.getName(), change, msg, new Date(stats.getAnalysisTimestamp()) });
                stats.setWarned(true);
                backgroundState.offer(table);
            }
        }
    }

    //
    // JmxManageable
    //

    @Override
    public JmxObjectInfo getJmxObjectInfo() {
        return new JmxObjectInfo("IndexStatistics", 
                                 new JmxBean(), 
                                 IndexStatisticsMXBean.class);
    }

    class JmxBean implements IndexStatisticsMXBean {
        @Override
        public String dumpIndexStatistics(String schema, String toFile) throws IOException {
            try(Session session = sessionService.createSession()) {
                File file = new File(toFile);
                try (FileWriter writer = new FileWriter(file)) {
                    dumpInternal(session, writer, schema);
                }
                return file.getAbsolutePath();
            }
        }

        @Override
        public String dumpIndexStatisticsToString(String schema) throws IOException {
            StringWriter writer = new StringWriter();
            try(Session session = sessionService.createSession()) {
                dumpInternal(session, writer, schema);
                writer.close();
                return writer.toString();
            }
        }

        @Override
        public void loadIndexStatistics(String schema, String fromFile)  throws IOException {
            try(Session session = sessionService.createSession()) {
                File file = new File(fromFile);
                try(TransactionService.CloseableTransaction txn = txnService.beginCloseableTransaction(session)) {
                    AbstractIndexStatisticsService.this.loadIndexStatistics(session, schema, file);
                    txn.commit();
                }
            } catch(RuntimeException ex) {
                log.error("Error loading " + schema, ex);
                throw ex;
            }
        }

        private void dumpInternal(Session session, Writer writer, String schema) throws IOException {
            try(TransactionService.CloseableTransaction txn = txnService.beginCloseableTransaction(session)) {
                AbstractIndexStatisticsService.this.dumpIndexStatistics(session, schema, writer);
                txn.commit();
            } catch(RuntimeException ex) {
                log.error("Error dumping " + schema, ex);
                throw ex;
            }
        }
    }


    //
    // TableListener
    //

    @Override
    public void onCreate(Session session, Table table) {
        // None
    }

    @Override
    public void onDrop(Session session, Table table) {
        deleteIndexStatistics(session, table.getIndexesIncludingInternal());
        deleteIndexStatistics(session, table.getGroupIndexes());
    }

    @Override
    public void onTruncate(Session session, Table table, boolean isFast) {
        onDrop(session, table);
    }

    @Override
    public void onCreateIndex(Session session, Collection<? extends Index> indexes) {
        // None
    }

    @Override
    public void onDropIndex(Session session, Collection<? extends Index> indexes) {
        deleteIndexStatistics(session, indexes);
    }


    //
    // Internal
    //

    /** Convert from new-format histogram to old for adapter. */
    protected TableStatistics.Histogram indexStatisticsToHistogram(Session session, Index index, Key key) {
        IndexStatistics stats = getIndexStatistics(session, index);
        if (stats == null) {
            return null;
        }
        int nkeys = index.getKeyColumns().size();
        Histogram fromHistogram = stats.getHistogram(0, nkeys);
        if (fromHistogram == null) {
            return null;
        }
        IndexRowComposition indexRowComposition = index.indexRowComposition();
        RowDef indexRowDef = index.leafMostTable().rowDef();
        TableStatistics.Histogram toHistogram = new TableStatistics.Histogram(index.getIndexId());
        RowData indexRowData = new RowData(new byte[4096]);
        Object[] indexValues = new Object[indexRowDef.getFieldCount()];
        long count = 0;
        for (HistogramEntry entry : fromHistogram.getEntries()) {
            // Decode the key.
            int keylen = entry.getKeyBytes().length;
            System.arraycopy(entry.getKeyBytes(), 0, key.getEncodedBytes(), 0, keylen);
            key.setEncodedSize(keylen);
            key.indexTo(0);
            int depth = key.getDepth();
            // Copy key fields to index row.
            for (int i = 0; i < nkeys; i++) {
                int field = indexRowComposition.getFieldPosition(i);
                if (--depth >= 0) {
                    indexValues[field] = key.decode();
                } else {
                    indexValues[field] = null;
                }
            }
            indexRowData.createRow(indexRowDef, indexValues);
            // Partial counts to running total less than key.
            count += entry.getLessCount();
            toHistogram.addSample(new TableStatistics.HistogramSample(indexRowData.copy(), count));
            count += entry.getEqualCount();
        }
        // Add final entry with all nulls.
        Arrays.fill(indexValues, null);
        indexRowData.createRow(indexRowDef, indexValues);
        toHistogram.addSample(new TableStatistics.HistogramSample(indexRowData.copy(), count));
        return toHistogram;
    }

    private static AkibanInformationSchema createStatsTables(SchemaManager schemaManager) {
        NewAISBuilder builder = AISBBasedBuilder.create(INDEX_STATISTICS_TABLE_NAME.getSchemaName(),
                                                        schemaManager.getTypesTranslator());
        builder.table(INDEX_STATISTICS_TABLE_NAME.getTableName())
                .colBigInt("table_id", false)
                .colBigInt("index_id", false)
                .colTimestamp("analysis_timestamp", true)
                .colBigInt("row_count", true)
                .colBigInt("sampled_count", true)
                .pk("table_id", "index_id");
        builder.table(INDEX_STATISTICS_ENTRY_TABLE_NAME.getTableName())
                .colBigInt("table_id", false)
                .colBigInt("index_id", false)
                .colInt("column_count", false)
                .colInt("item_number", false)
                .colString("key_string", 2048, true, "latin1")
                .colVarBinary("key_bytes", 4096, true)
                .colBigInt("eq_count", true)
                .colBigInt("lt_count", true)
                .colBigInt("distinct_count", true)
                .pk("table_id", "index_id", "column_count", "item_number")
                .joinTo(INDEX_STATISTICS_TABLE_NAME.getSchemaName(), INDEX_STATISTICS_TABLE_NAME.getTableName(), "fk_0")
                .on("table_id", "table_id")
                .and("index_id", "index_id");
        return builder.ais(true);
    }

    private void registerStatsTables() {
        AkibanInformationSchema ais = createStatsTables(schemaManager);
        schemaManager.registerStoredInformationSchemaTable(ais.getTable(INDEX_STATISTICS_TABLE_NAME), INDEX_STATISTICS_TABLE_VERSION);
        schemaManager.registerStoredInformationSchemaTable(ais.getTable(INDEX_STATISTICS_ENTRY_TABLE_NAME), INDEX_STATISTICS_TABLE_VERSION);
    }

    class BackgroundState implements Runnable {
        private final Queue<IndexName> queue = new ArrayDeque<>();
        private boolean active;
        private Thread thread = null;

        public BackgroundState(boolean active) {
            this.active = active;
        }

        public synchronized void offer(Table table) {
            for (Index index : table.getIndexes()) {
                offer(index);
            }
        }

        public synchronized void offer(Index index) {
            if (active) {
                IndexName entry = index.getIndexName();
                if (!queue.contains(entry)) {
                    if (queue.offer(entry)) {
                        if (thread == null) {
                            thread = new Thread(this, "IndexStatistics-Background");
                            thread.start();
                        }
                    }
                }
            }
        }

        public synchronized void removeAll(Map<Index,IndexStatistics> updates) {
            for (Index index : updates.keySet()) {
                remove(index);
            }
        }

        public synchronized void remove(Index index) {
            queue.remove(index.getIndexName());
        }

        public synchronized void stop() {
            active = false;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(1000); // Wait a little for it to shut down.
                }
                catch (InterruptedException ex) {
                }
            }
        }

        @Override
        public void run() {
            try (Session session = sessionService.createSession()) {
                while (active) {
                    IndexName entry;
                    synchronized (this) {
                        entry = queue.poll();
                        if (entry == null) {
                            thread = null;
                            break;
                        }
                    }
                    updateIndex(session, entry);
                }
            }
            catch (Exception ex) {
                log.warn("Error in background", ex);
                // TODO: Disable background altogether by turning off active?
                synchronized (this) {
                    queue.clear();
                    thread = null;
                }
            }
        }

        private void updateIndex(Session session, IndexName indexName) {
            Map<Index,IndexStatistics> statistics;
            try (TransactionService.CloseableTransaction txn = txnService.beginCloseableTransaction(session)) {
                Index index = null;
                AkibanInformationSchema ais = schemaManager.getAis(session);
                Table table = ais.getTable(indexName.getFullTableName());
                if (table != null)
                    index = table.getIndex(indexName.getName());
                if (index == null) {
                    Group group = ais.getGroup(indexName.getFullTableName());
                    if (group != null)
                        index = group.getIndex(indexName.getName());
                }
                if (index == null) return; // Could have been dropped in the meantime.
                statistics = updateIndexStatistics(session, Collections.singletonList(index), true);
                txn.commit();
                log.info("Automatically updated statistics for {}", indexName);
            }
            cache.putAll(statistics);
        }
    }
}
