package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.File;

import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;
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
        progressListener.beginTask("Validating files", 1);
        try {
            ShapefileReader reader = new ShapefileReader(file);
            // Iterate over all features to ensure file is readable, geometries are valid, and can be reprojected.
            Envelope envelope = reader.wgs84Bounds();
            checkWgsEnvelopeSize(envelope);
            reader.wgs84Stream().forEach(f -> {
                checkState(envelope.contains(((Geometry)f.getDefaultGeometry()).getEnvelopeInternal()));
            });
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(envelope);
            dataSource.attributes = reader.attributes();
            dataSource.geometryType = reader.geometryType();
            dataSource.featureCount = reader.featureCount();
        } catch (FactoryException | TransformException e) {
            throw new RuntimeException("Shapefile transform error. Try uploading an unprojected (EPSG:4326) file.", e);
        } catch (Exception e) {
            // Must catch because ShapefileReader throws a checked IOException.
            throw new RuntimeException("Error parsing shapefile. Ensure the files you uploaded are valid.", e);
        }
    }

}
