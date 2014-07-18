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

import com.foundationdb.sql.optimizer.plan.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Convert nested loop join into map.
 * This rule only does the immediate conversion to a Map. The map
 * still needs to be folded after conditions have moved down and so on.
 */
public class NestedLoopMapper extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(NestedLoopMapper.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    static class NestedLoopsJoinsFinder implements PlanVisitor, ExpressionVisitor {
        List<JoinNode> result = new ArrayList<>();

        public List<JoinNode> find(PlanNode root) {
            root.accept(this);
            return result;
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
            if (n instanceof JoinNode) {
                JoinNode j = (JoinNode)n;
                switch (j.getImplementation()) {
                case NESTED_LOOPS:
                case BLOOM_FILTER:
                    result.add(j);
                }
            }
            return true;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            return true;
        }
    }

    @Override
    public void apply(PlanContext planContext) {
        BaseQuery query = (BaseQuery)planContext.getPlan();
        List<JoinNode> joins = new NestedLoopsJoinsFinder().find(query);
        for (JoinNode join : joins) {
            PlanNode outer = join.getLeft();
            PlanNode inner = join.getRight();
            // TODO #2 it might be a good idea to attach table sources to the conditions
            // so that I don't have to search them again. Maybe.
            // TODO what about having multiple inner and outers? the one in the middle can
            // see the sources of the outer most one
            // TODO update other code changes to use hasJoinConditions
            if (join.hasJoinConditions()) {
                outer = moveConditionsToOuterNode(outer, join.getJoinConditions(), query.getOuterTables());
                if (join.hasJoinConditions())
                    inner = new Select(inner, join.getJoinConditions());
            }
            PlanNode map;
            switch (join.getImplementation()) {
            case NESTED_LOOPS:
                map = new MapJoin(join.getJoinType(), outer, inner);
                break;
            case BLOOM_FILTER:
                {
                    HashJoinNode hjoin = (HashJoinNode)join;
                    BloomFilter bf = (BloomFilter)hjoin.getHashTable();
                    map = new BloomFilterFilter(bf, hjoin.getMatchColumns(),
                                                outer, inner);
                    PlanNode loader = hjoin.getLoader();
                    loader = new Project(loader, hjoin.getHashColumns());
                    map = new UsingBloomFilter(bf, loader, map);
                }
                break;
            default:
                assert false : join;
                map = join;
            }
            join.getOutput().replaceInput(join, map);
        }
    }


    private PlanNode moveConditionsToOuterNode(PlanNode planNode, ConditionList conditions,
                                               Set<ColumnSource> outerSources) {
        ConditionList selectConditions = new ConditionList();
        Iterator<ConditionExpression> iterator = conditions.iterator();
        while (iterator.hasNext()) {
            ConditionExpression condition = iterator.next();
            List<TableSource> tableSources = new GroupJoinFinder.ConditionTableSources().find(condition);
            // TODO getOuterTables is a bunch of ColumnSources should I worry about other kinds of ColumnSources
            // here or farther down
            tableSources.removeAll(outerSources);
            PlanNodeProvidesSourcesChecker checker = new PlanNodeProvidesSourcesChecker(tableSources, planNode);
            if (checker.run()) {
                selectConditions.add(condition);
                iterator.remove();
            }
        }
        return selectConditions.isEmpty() ? planNode : new Select(planNode, selectConditions);
    }

    private static class PlanNodeProvidesSourcesChecker implements PlanVisitor {

        private final List<TableSource> tableSources;
        private final PlanNode planNode;

        private PlanNodeProvidesSourcesChecker(List<TableSource> tableSources, PlanNode node) {
            this.tableSources = tableSources;
            this.planNode = node;
        }

        public boolean run() {
            planNode.accept(this);
            return tableSources.isEmpty();
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            if (n instanceof ColumnSource) {
                tableSources.remove(n);
                return false;
            }
            if (n instanceof Subquery) {
                // TODO make sure this is right. probably change it to throw an exception for a bit
                return false;
            }
            return true;
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return false;
        }

        @Override
        public boolean visit(PlanNode n) {
            if (n instanceof ColumnSource) {
                tableSources.remove(n);
                return false;
            }
            if (n instanceof Subquery) {
                return false;
            }
            return true;
        }
    }

}
