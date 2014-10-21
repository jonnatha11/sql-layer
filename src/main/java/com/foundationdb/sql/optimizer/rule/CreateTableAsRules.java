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

package com.foundationdb.sql.optimizer.rule;

import com.foundationdb.server.error.UnsupportedCreateSelectException;
import com.foundationdb.sql.optimizer.plan.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** {@Create Table As Rules} takes in a planContext then visits all nodes that are
 * instances of TableSource and replaces them with CreateAs plan, these are used
 * later on to put EmitBoundRow_Nexted operators which will be used for insertion
 * and deletion from an online Create Table As query*/

/**
 * TODO in future versions this could take a specified table name or id
 * then only change these tablesources, this would be necessary if in future versions
 * we accept queries with union, intersect, except, join, etc
 */
 public class CreateTableAsRules extends BaseRule {
    private static final Logger logger = LoggerFactory.getLogger(SortSplitter.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {

        Results results =  new CreateTableAsFinder().find(plan.getPlan());
        CreateAs createAs = null;
        for (TableSource tableSource : results.tables) {
            createAs = transform(tableSource);
        }
        assert(createAs != null);
        for (Project project : results.projects) {
            transform(project, createAs);
        }
    }

    protected CreateAs transform(TableSource tableSource) {
        CreateAs createAs = new CreateAs();
        createAs.setOutput(tableSource.getOutput());
        (tableSource.getOutput()).replaceInput(tableSource, createAs);
        createAs.setTableSource(tableSource);
        return createAs;
    }

    protected void transform(Project project, CreateAs createAs){
        for (int i = 0; i < project.getFields().size(); i++){
            if(project.getFields().get(i) instanceof ColumnExpression) {
                ColumnExpression expression = (ColumnExpression) project.getFields().get(i);
                project.getFields().remove(i);
                project.getFields().add(i, new ColumnExpression(expression, createAs));
            }
        }
    }

    static class Results {

        public List<TableSource> tables = new ArrayList<>();
        public List<Project> projects = new ArrayList<>();
    }

    static class CreateTableAsFinder implements PlanVisitor {

        Results results;

        public Results find(PlanNode root) {
            results = new Results();
            root.accept(this);
            return results;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            if(isIllegalPlan(n)){
                throw new UnsupportedCreateSelectException();
            }
            if (n instanceof TableSource)
                results.tables.add((TableSource) n);
            else if (n instanceof Project) {
                results.projects.add((Project) n);
            }
            return true;
        }

        public boolean isIllegalPlan(PlanNode n) {
            // Only the simplest select from a single table is allowed.
            return !(n instanceof DMLStatement || n instanceof InsertStatement ||
                     n instanceof Project || n instanceof Select || n instanceof TableSource);
        }
    }
}