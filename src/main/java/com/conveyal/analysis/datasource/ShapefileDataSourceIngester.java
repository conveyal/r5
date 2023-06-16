package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;

import static com.conveyal.r5.common.GeometryUtils.checkWgsEnvelopeSize;
import static com.google.common.base.Preconditions.checkState;

/**
 * Logic to create SpatialDataSource metadata from a Shapefile.
 */
public class ShapefileDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    @Override
    public DataSource dataSource () {
        return dataSource;
    }

    public ShapefileDataSourceIngester () {
        // Note we're using the no-arg constructor creating a totally empty object.
        // Its fields will be set later by the enclosing DataSourceUploadAction.
        this.dataSource = new SpatialDataSource();
        dataSource.fileFormat = FileStorageFormat.SHP;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        progressListener.beginTask("Validating uploaded shapefile", 2);
        progressListener.setWorkProduct(dataSource.toWorkProduct());
        try {
            ShapefileReader reader = new ShapefileReader(file);
            // Iterate over all features to ensure file is readable, geometries are valid, and can be reprojected.
            // Note that the method reader.wgs84Bounds() transforms the envelope in projected coordinates to WGS84,
            // which does not necessarily include all the points transformed individually.
            Envelope envelope = new Envelope();
            reader.wgs84Stream().forEach(f -> {
                envelope.expandToInclude(((Geometry)f.getDefaultGeometry()).getEnvelopeInternal());
            });
            checkWgsEnvelopeSize(envelope, "Shapefile");
            reader.close();
            progressListener.increment();
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(envelope);
            dataSource.attributes = reader.attributes();
            dataSource.geometryType = reader.geometryType();
            dataSource.featureCount = reader.featureCount();
            dataSource.coordinateSystem = reader.crs.getName().getCode();
            progressListener.increment();
        } catch (FactoryException | TransformException e) {
            throw new DataSourceException(
                "Shapefile transform error. Try uploading an unprojected WGS84 (EPSG:4326) file.", e
            );
        } catch (IOException e) {
            // ShapefileReader throws a checked IOException.
            throw new DataSourceException("Error parsing shapefile. Ensure the files you uploaded are valid.", e);
        }
    }

}
