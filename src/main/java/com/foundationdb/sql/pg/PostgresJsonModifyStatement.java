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

package com.foundationdb.sql.pg;

import com.foundationdb.sql.optimizer.plan.CostEstimate;
import static com.foundationdb.sql.pg.PostgresJsonCompiler.JsonResultColumn;
import static com.foundationdb.sql.pg.PostgresJsonStatement.jsonColumnNames;
import static com.foundationdb.sql.pg.PostgresJsonStatement.jsonColumnTypes;

import com.foundationdb.qp.operator.Operator;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.server.types.TInstance;

import java.util.*;

public class PostgresJsonModifyStatement extends PostgresModifyOperatorStatement
{
    private List<JsonResultColumn> resultColumns;
    private TInstance colTInstance;

    public PostgresJsonModifyStatement(PostgresOperatorCompiler compiler) {
        super(compiler);
        colTInstance = compiler.getTypesTranslator().typeForString();
    }

    public void init(String statementType, Operator resultOperator, RowType resultRowType,
                     List<JsonResultColumn> resultColumns,
                     PostgresType[] parameterTypes,
                     CostEstimate costEstimate,
                     boolean putInCache) {
        super.init(statementType, resultOperator, resultRowType,
                   // Looks like just one unlimited VARCHAR to the client.
                   jsonColumnNames(), jsonColumnTypes(colTInstance),
                   parameterTypes, costEstimate,  
                   putInCache);
        this.resultColumns = resultColumns;
    }

    @Override
    protected PostgresOutputter<Row> getRowOutputter(PostgresQueryContext context) {
        return new PostgresJsonOutputter(context, this, 
                                         resultColumns, getColumnTypes().get(0));
    }
    
}
