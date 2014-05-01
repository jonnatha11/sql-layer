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

package com.foundationdb.ais.model.validation;

import com.foundationdb.ais.model.AbstractVisitor;
import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.server.error.GeneratorWrongDatatypeException;
import com.foundationdb.server.error.UnsupportedColumnDataTypeException;
import com.foundationdb.server.error.UnsupportedIndexDataTypeException;
import com.foundationdb.server.types.common.types.TypeValidator;

class SupportedColumnTypes implements AISValidation {
    @Override
    public void validate(AkibanInformationSchema ais, AISValidationOutput output) {
        ColumnTypeVisitor visitor = new ColumnTypeVisitor(output, ais);
        ais.visit(visitor);
    }

    private static class ColumnTypeVisitor extends AbstractVisitor {
        private final AISValidationOutput failures;
        private final AkibanInformationSchema sourceAIS;

        private ColumnTypeVisitor(AISValidationOutput failures, AkibanInformationSchema sourceAIS) {
            this.failures = failures;
            this.sourceAIS = sourceAIS;
        }

        @Override
        public void visit(Column column) {
            if (!TypeValidator.isSupportedForColumn(column.getType())) {
                failures.reportFailure(new AISValidationFailure (
                        new UnsupportedColumnDataTypeException(column.getTable().getName(),
                                column.getName(), column.getTypeName())));
            }
            
            if (column.getDefaultIdentity() != null && !TypeValidator.isSupportedForGenerator(column.getType())) {
                failures.reportFailure(new AISValidationFailure(
                        new GeneratorWrongDatatypeException (column.getTable().getName(),
                                column.getName(), column.getTypeName())));
            }
        }

        @Override
        public void visit(IndexColumn indexColumn) {
            if (!TypeValidator.isSupportedForIndex(indexColumn.getColumn().getType())) {
                failures.reportFailure(new AISValidationFailure (
                        new UnsupportedIndexDataTypeException (
                                new TableName (indexColumn.getIndex().getIndexName().getSchemaName(),
                                indexColumn.getIndex().getIndexName().getTableName()),
                                indexColumn.getIndex().getIndexName().getName(),
                                indexColumn.getColumn().getName(),
                                indexColumn.getColumn().getTypeName())));
            }
        }
    }
}
