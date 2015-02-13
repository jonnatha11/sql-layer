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

package com.foundationdb.sql.embedded;

import com.foundationdb.qp.operator.Operator;

abstract class ExecutableOperatorStatement extends ExecutableStatement
{
    protected Operator resultOperator;
    protected long aisGeneration;
    protected JDBCResultSetMetaData resultSetMetaData;
    protected JDBCParameterMetaData parameterMetaData;
    
    protected ExecutableOperatorStatement(Operator resultOperator,
                                          long aisGeneration,
                                          JDBCResultSetMetaData resultSetMetaData,
                                          JDBCParameterMetaData parameterMetaData) {
        this.resultOperator = resultOperator;
        this.aisGeneration = aisGeneration;
        this.resultSetMetaData = resultSetMetaData;
        this.parameterMetaData = parameterMetaData;
    }

    public Operator getResultOperator() {
        return resultOperator;
    }

    @Override
    public JDBCResultSetMetaData getResultSetMetaData() {
        return resultSetMetaData;
    }

    @Override
    public JDBCParameterMetaData getParameterMetaData() {
        return parameterMetaData;
    }

    @Override
    public TransactionMode getTransactionMode() {
        return TransactionMode.READ;
    }

    @Override
    public TransactionAbortedMode getTransactionAbortedMode() {
        return TransactionAbortedMode.NOT_ALLOWED;
    }

    @Override
    public AISGenerationMode getAISGenerationMode() {
        return AISGenerationMode.NOT_ALLOWED;
    }

    @Override
    public long getAISGeneration() {
        return aisGeneration;
    }

}
