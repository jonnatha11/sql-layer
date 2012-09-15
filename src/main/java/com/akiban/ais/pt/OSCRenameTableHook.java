/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.ais.pt;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.ais.util.TableChange;
import com.akiban.message.MessageRequiredServices;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.service.session.Session;

import java.util.List;
import java.util.Map;

/** Hook for <code>RenameTableRequest</code>.
 * The single statement <code>RENAME TABLE xxx TO _xxx_old, _xxx_new TO xxx</code>
 * arrives from the adapter as two requests. For simplicity, and since
 * rename is cheap, the first is allowed to proceed, but the target name is noted.
 * The second is where all the work this has been leading up to happens: the
 * alter is performed on <code>xxx</code> and new is renamed to old.
 * In this way, at the end of the atomic rename, the client sees the tables that
 * it expects with the shape that it expects. But not arrived at in
 * the way it orchestrated.
 */
public class OSCRenameTableHook
{
    private final MessageRequiredServices requiredServices;

    public OSCRenameTableHook(MessageRequiredServices requiredServices) {
        this.requiredServices = requiredServices;
    }

    public boolean before(Session session, TableName oldName, TableName newName) {
        if ((newName.getTableName().charAt(0) == '_') &&
            newName.getTableName().endsWith("_old") &&
            newName.getSchemaName().equals(oldName.getSchemaName())) {
            return beforeOld(session, oldName, newName);
        }
        else if ((oldName.getTableName().charAt(0) == '_') &&
                 oldName.getTableName().endsWith("_new") &
                 oldName.getSchemaName().equals(newName.getSchemaName())) {
            return beforeNew(session, oldName, newName);
        }
        return true;            // Allow rename to continue.
    }

    /** Handle first rename.
     * We are being told to rename <code>xxx</code> to <code>_xxx_old</code>.
     * If this is OSC, there is an <code>_xxx_new</code> that points to <code>xxx</code>.
     * Except that either one of those _ names might have multiple _'s for uniqueness.
     */
    protected boolean beforeOld(Session session, TableName oldName, TableName newName) {
        AkibanInformationSchema ais = requiredServices.schemaManager().getAis(session);
        String schemaName = oldName.getSchemaName();
        String tableName = oldName.getTableName();

        // Easy case first.
        UserTable table = ais.getUserTable(schemaName, "_" + tableName + "_new");
        if ((table != null) &&
            (table.getPendingOSC() != null) &&
            (table.getPendingOSC().getOriginalName().equals(tableName))) {
            table.getPendingOSC().setCurrentName(newName.getTableName());
            return true;
        }
        
        // Try harder.
        for (Map.Entry<String,UserTable> entry : ais.getSchema(schemaName).getUserTables().entrySet()) {
            if (entry.getKey().contains(tableName) &&
                (table.getPendingOSC() != null) &&
                (table.getPendingOSC().getOriginalName().equals(tableName))) {
                table.getPendingOSC().setCurrentName(newName.getTableName());
                return true;
            }
        }

        return true;
    }

    /** Handle second rename.
     * Undo the first rename. So far that is the only change to real data that has
     * been made. If we fail now because of grouping constraints, we are in as good
     * shape as is possible under those circumstances.
     * Then do the alter on the original table.
     * Then rename the temp table to the name that OSC will DROP.
     */
    protected boolean beforeNew(Session session, TableName oldName, TableName newName) {
        AkibanInformationSchema ais = requiredServices.schemaManager().getAis(session);
        DDLFunctions ddl = requiredServices.dxl().ddlFunctions();
        UserTable tempTable = ais.getUserTable(oldName);
        if (tempTable == null) return true;
        PendingOSC osc = tempTable.getPendingOSC();
        if ((osc == null) ||
            (osc.getCurrentName() == null))
            return true;
        TableName currentName = new TableName(oldName.getSchemaName(), osc.getCurrentName());
        if (ais.getUserTable(currentName) == null)
            return true;
        
        TableName origName = new TableName(oldName.getSchemaName(), osc.getOriginalName());
        ddl.renameTable(session, currentName, origName);
        
        doAlter(session, origName, oldName, osc);

        ddl.renameTable(session, oldName, currentName);
        return false;
    }

    /** Do the actual alter, corresponding to what was done previously
     * on a temporary copy of the table. Because we have this copy as
     * a template, not very much information needs to be remembered
     * about the earlier alter.
     */
    protected void doAlter(Session session, TableName realName, TableName alteredName, PendingOSC changes) {
        AkibanInformationSchema ais = requiredServices.schemaManager().getAis(session);
        DDLFunctions ddl = requiredServices.dxl().ddlFunctions();
        UserTable realTable = ais.getUserTable(realName);
        UserTable tempTable = ais.getUserTable(alteredName);
        
    }
}
