/**
 * 
 */
package com.akiban.cserver.store;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileOutputStream;

import com.akiban.cserver.FieldDef;
import com.akiban.cserver.RowData;
import com.akiban.cserver.RowDef;
//import com.akiban.cserver.RowDefCache;
//import com.akiban.cserver.message.ScanRowsRequest;
import com.persistit.Exchange;
import com.persistit.Key;
//import com.persistit.exception.PersistitException;
import com.akiban.vstore.ColumnArray;
import com.akiban.vstore.ColumnDescriptor;
import com.akiban.vstore.VMeta;

/**
 * @author percent
 * @author posulliv
 */
public class VStore {

    public static void setDataPath(final String path)
    {
        DATA_PATH = path;
    }
    
    public VStore(Store store) {
        this.hstore = store;
    }
    
    public void constructColumnDescriptors()
        throws Exception
    {
        String prefix = DATA_PATH + "/";
        columnArrays = new ArrayList<ColumnArray>();
        columnDescriptors = new ArrayList<ColumnDescriptor>();
        for (Map.Entry<String, String> entry : columnList.entrySet()) {
            try {
                File columnData = new File(entry.getValue());
                ColumnArray colArr = new ColumnArray(columnData);
                columnArrays.add(colArr);
                ColumnInfo info = columnInfo.get(entry.getKey());
                ColumnDescriptor descrip = new ColumnDescriptor(prefix,
                                                                info.getSchemaName(), 
                                                                info.getTableName(),
                                                                info.getColumnName(), 
                                                                info.getTableId(), 
                                                                info.getOrdinal(), 
                                                                info.getSize(), 
                                                                info.getCount());
                columnDescriptors.add(descrip);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        /* hard-code metadata file name for now */
        String metaFileName = DATA_PATH + "/vstoreMetaFile";
        File metaFile = new File(metaFileName);
        VMeta vmeta = new VMeta(columnDescriptors);
        vmeta.write(metaFile);
    }

    public ArrayList<ColumnDescriptor> getColumnDescriptors()
    {
        return columnDescriptors;
    }

    public List<ColumnArray> getColumnArrays()
    {
        return columnArrays;
    }

    /**
     * @see com.akiban.cserver.store.Store#writeRowForBulkLoad(com.persistit.Exchange,
     *      com.akiban.cserver.RowDef, com.akiban.cserver.RowData, int[],
     *      com.akiban.cserver.FieldDef[][], java.lang.Object[][])
     */
    public int writeRowForBulkLoad(final Exchange hEx, 
                                   final RowDef rowDef,
                                   final RowData rowData, 
                                   final int[] ordinals, 
                                   FieldDef[][] fieldDefs,
                                   Object[][] hKeyValues) 
        throws Exception
    {
        final Key hkey = constructHKey(rowDef, ordinals, fieldDefs, hKeyValues);
        String schemaName = rowDef.getSchemaName();
        String tableName = rowDef.getTableName();
        String prefix = DATA_PATH + "/" + schemaName + tableName;

        /*
         * Go through each column in this row and ensure that a file exists for that column. For
         * now, we have 1 file per column by default. If a file does not exist, then create it.
         * @todo: for now, the name used per file is the column name. Need to discuss if this needs
         * to be changed or not.
         */
        for (int i = 0; i < rowDef.getFieldCount(); i++) {
            FieldDef field = rowDef.getFieldDef(i);
            String columnName = field.getName();
            String columnFileName = prefix + columnName;
            File columnData = new File(columnFileName);
            if (! columnData.exists()) {
                boolean ret = columnData.createNewFile();
                if (! ret) {
                    throw new Exception();
                }
                columnList.put(columnName, columnFileName);
                ColumnInfo info = new ColumnInfo(columnName, 
                    tableName, 
                    schemaName, 
                    rowDef.getRowDefId(),
                    i);
                columnInfo.put(columnName, info);
            } 

            ColumnInfo info = columnInfo.get(columnName); /* @todo: temporary only */
            /* insert the data */
            final long locationAndSize = rowDef.fieldLocation(rowData, i);
            if (0 == locationAndSize) {
                /* NULL field. @todo: how do we handle NULL's in the V store? */
            }
            int offset = (int) locationAndSize;
            int size = (int) (locationAndSize >>> 32);
            byte[] bytes = rowData.getBytes();
            FileOutputStream fout = new FileOutputStream(columnData, true);
            fout.write(bytes, offset, size);

            info.incrementCount();
            info.setSize(size);
            columnInfo.put(columnName, info);
        }

        return 0;
    }

    private Key constructHKey(final RowDef rowDef,
                              final int[] ordinals,
                              final FieldDef[][] fieldDefs,
                              final Object[][] hKeyValues)
        throws Exception
    {
        final Key hKey = new Key(((PersistitStore) hstore).getDb());
        hKey.clear();
        for (int i = 0; i < hKeyValues.length; i++) {
            hKey.append(ordinals[i]);
            Object[] tableHKeyValues = hKeyValues[i];
            FieldDef[] tableFieldDefs = fieldDefs[i];
            for (int j = 0; j < tableHKeyValues.length; j++) {
                tableFieldDefs[j].getEncoding().toKey(tableFieldDefs[j], 
                                                      tableHKeyValues[j],
                                                      hKey);
            }
        }
        return hKey;
    }

    /*
     * Temporary class only being used for testing purposes right now to carry metadata about
     * columns. Once the metadata for column is actually stored in some kind of header on disk, we
     * shouldn't need this class anymore.
     */
    class ColumnInfo
    {
        public ColumnInfo(int columnSize)
        {
            this.columnSize = columnSize;
            this.count = 0;
        }

        public ColumnInfo(String columnName, String tableName, String schemaName, int tableId, int ordinal)
        {
            this.tableId = tableId;
            this.ordinal = ordinal;
            this.columnSize = 0;
            this.count = 0;
            this.columnName = columnName;
            this.tableName = tableName;
            this.schemaName = schemaName;
        }

        public ColumnInfo()
        {
            this.columnSize = 0;
            this.count = 0;
        }

        public void incrementCount()
        {
            count++;
        }

        public void setSize(int size)
        {
            if (0 == columnSize) {
                columnSize = size;
            }
        }

        public int getSize()
        {
            return columnSize;
        }

        public int getCount()
        {
            return count;
        }

        public int getTableId()
        {
            return tableId;
        }

        public int getOrdinal()
        {
            return ordinal;
        }

        public String getSchemaName()
        {
            return schemaName;
        }

        public String getTableName()
        {
            return tableName;
        }

        public String getColumnName()
        {
            return columnName;
        }
        
        private int tableId;
        private int ordinal;
        private int columnSize;
        private int count;
        private String schemaName;
        private String tableName;
        private String columnName;
    }

    static String DATA_PATH = "/usr/local/akiba/data";
    
    private Store hstore;
    private HashMap<String, String> columnList = new HashMap<String, String>();
    private HashMap<String, ColumnInfo> columnInfo = new HashMap<String, ColumnInfo>();
    private List<ColumnArray> columnArrays;
    private ArrayList<ColumnDescriptor> columnDescriptors;
}
