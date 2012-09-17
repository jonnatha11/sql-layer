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

package com.akiban.sql.embedded;

import com.akiban.server.types.AkType;
import com.akiban.server.types3.TInstance;
import com.akiban.sql.optimizer.TypesTranslation;
import com.akiban.sql.types.DataTypeDescriptor;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

import java.util.List;

public class JDBCParameterMetaData implements ParameterMetaData
{
    protected static class JDBCParameterType {
        private int jdbcType;
        private DataTypeDescriptor sqlType;
        private TInstance tInstance;

        protected JDBCParameterType(int jdbcType, DataTypeDescriptor sqlType, 
                                    TInstance tInstance) {
            this.jdbcType = jdbcType;
            this.sqlType = sqlType;
            this.tInstance = tInstance;
        }

        public int getJDBCType() {
            return jdbcType;
        }

        public DataTypeDescriptor getSQLType() {
            return sqlType;
        }

        public TInstance getTInstance() {
            return tInstance;
        }

        public AkType getAkType() {
            return TypesTranslation.sqlTypeToAkType(sqlType);
        }

        public int getScale() {
            return sqlType.getScale();
        }

        public int getPrecision() {
            return sqlType.getPrecision();
        }

        public boolean isNullable() {
            return sqlType.isNullable();
        }

        public String getTypeName() {
            return sqlType.getTypeName();
        }
    }
    
    private List<JDBCParameterType> params;

    protected JDBCParameterMetaData(List<JDBCParameterType> params) {
        this.params = params;
    }

    protected JDBCParameterType getParameter(int param) {
        return params.get(param - 1);
    }

    /* Wrapper */

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("Not supported");
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    /* ParameterMetaData */

    @Override
    public int getParameterCount() throws SQLException {
        return params.size();
    }

    @Override
    public int isNullable(int param) throws SQLException {
        return getParameter(param).isNullable() ? parameterNullable : parameterNoNulls;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        return JDBCResultSetMetaData.isTypeSigned(getParameter(param).getAkType());
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        return getParameter(param).getPrecision();
    }

    @Override
    public int getScale(int param) throws SQLException {
        return getParameter(param).getScale();
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        return getParameter(param).getJDBCType();
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        return getParameter(param).getTypeName();
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        return JDBCResultSetMetaData.getTypeClassName(getParameter(param).getAkType());
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        return parameterModeIn;
    }
}
