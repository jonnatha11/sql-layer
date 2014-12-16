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

package com.foundationdb.sql.optimizer.plan;

public interface PlanNode extends PlanElement
{
    public PlanWithInput getOutput();

    public void setOutput(PlanWithInput output);

    public boolean accept(PlanVisitor v);

    /** One-line summary of just this node.
     * @param configuration configuration options for how the plan should be printed
     */
    public String summaryString(SummaryConfiguration configuration);

    /** Hierarchical format of this node and any inputs.
     * @param configuration configuration options for how the plan should be printed
     */
    public String planString(SummaryConfiguration configuration);

    class SummaryConfiguration {
        public final boolean includeRowTypes;
        public final boolean includeIndexTableNames;

        public SummaryConfiguration(boolean includeRowTypes, boolean includeIndexTableNames) {
            this.includeRowTypes = includeRowTypes;
            this.includeIndexTableNames = includeIndexTableNames;
        }

        public static final SummaryConfiguration DEFAULT = new SummaryConfiguration(false, false);
    }
}
