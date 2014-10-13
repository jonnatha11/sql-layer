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

package com.foundationdb.qp.storeadapter.indexrow;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.server.rowdata.FieldDef;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.rowdata.RowDataSource;
import com.foundationdb.server.rowdata.RowDataValueSource;
import com.foundationdb.server.spatial.Spatial;
import com.foundationdb.server.types.TClass;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.server.types.common.BigDecimalWrapper;
import com.foundationdb.server.types.common.types.TBigDecimal;
import com.foundationdb.server.types.mcompat.mtypes.MBinary;
import com.foundationdb.server.types.mcompat.mtypes.MNumeric;
import com.geophile.z.Space;
import com.geophile.z.SpatialObject;
import com.geophile.z.spatialobject.d2.Point;
import com.geophile.z.spatialobject.jts.JTSBase;

import java.nio.ByteBuffer;

public class SpatialColumnHandler
{
    public SpatialColumnHandler(Index index)
    {
        space = index.space();
        dimensions = space.dimensions();
        assert index.dimensions() == dimensions;
        rowDataSource = new RowDataValueSource();
        firstSpatialField = index.firstSpatialArgument();
        lastSpatialField = index.lastSpatialArgument();
        int nColumns = lastSpatialField - firstSpatialField + 1;
        tinstances = new TInstance[nColumns];
        fieldDefs = new FieldDef[nColumns];
        int spatialColumns = lastSpatialField - firstSpatialField + 1;
        for (int c = 0; c < spatialColumns; c++) {
            IndexColumn indexColumn = index.getKeyColumns().get(firstSpatialField + c);
            Column column = indexColumn.getColumn();
            tinstances[c] = column.getType();
            fieldDefs[c] = column.getFieldDef();
        }
    }

    public boolean handleSpatialColumn(PersistitIndexRowBuffer persistitIndexRowBuffer, int indexField, long zValue)
    {
        assert zValue >= 0;
        if (indexField == firstSpatialField) {
            persistitIndexRowBuffer.pKey().append(zValue);
        }
        return indexField >= firstSpatialField && indexField <= lastSpatialField;
    }

    public long zValue(RowData rowData)
    {
        bind(rowData);
        return Spatial.shuffle(space, coords[0], coords[1]);
    }

    private void bind(RowData rowData)
    {
        if (lastSpatialField > firstSpatialField) {
            // Point coordinates stored in two columns
            assert dimensions == 2 : dimensions;
            double coord = Double.NaN;
            double x = Double.NaN;
            double y = Double.NaN;
            for (int d = 0; d < dimensions; d++) {
                rowDataSource.bind(fieldDefs[d], rowData);
                RowDataValueSource rowDataValueSource = (RowDataValueSource) rowDataSource;
                TClass tclass = tinstances[d].typeClass();
                if (tclass == MNumeric.DECIMAL) {
                    BigDecimalWrapper wrapper = TBigDecimal.getWrapper(rowDataValueSource, tinstances[d]);
                    coord = wrapper.asBigDecimal().doubleValue();
                } else if (tclass == MNumeric.BIGINT) {
                    coord = rowDataValueSource.getInt64();
                } else if (tclass == MNumeric.INT) {
                    coord = rowDataValueSource.getInt32();
                } else {
                    assert false : fieldDefs[d].column();
                }
                if (d == 0) {
                    x = coord;
                } else {
                    y = coord;
                }
                coords[d] = coord;
            }
            spatialObject = new Point(x, y);
        } else {
            assert false;
/*
            // Spatial object encoded in blob
            rowDataSource.bind(fieldDefs[0], rowData);
            RowDataValueSource rowDataValueSource = (RowDataValueSource) rowDataSource;
            TClass tclass = tinstances[0].typeClass();
            assert tclass == MBinary.BLOB : tclass;
            // TODO: Is this the place to anticipate GeoJSON also?
            JTSBase jtsObject = (JTSBase) spatialObject;

            ByteBuffer buffer = ByteBuffer.wrap(rowDataValueSource.getBytes());
            jtsObject.readFrom();

*/
        }
    }

    private final Space space;
    private final int dimensions;
    private final TInstance[] tinstances;
    private final FieldDef[] fieldDefs;
    private final RowDataSource rowDataSource;
    private final int firstSpatialField;
    private final int lastSpatialField;
    private SpatialObject spatialObject;
    private double[] coords = new double[2]; // Going away
}
