package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.locationtech.jts.geom.Envelope;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.File;

import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

/**
 * Logic to create SpatialDataSource metadata from a comma separated file.
 * Eventually we may want to support other separators like semicolon, tab, vertical bar etc.
 * Eventually this could also import non-spatial delimited text files.
 */
public class CsvDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    @Override
    public SpatialDataSource dataSource () {
        return dataSource;
    }

    public CsvDataSourceIngester () {
        this.dataSource = new SpatialDataSource();
        dataSource.fileFormat = FileStorageFormat.CSV;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        progressListener.beginTask("Scanning CSV file", 1);
        try {
            // TODO logic based on FreeFormPointSet.fromCsv() and Grid.fromCsv()
            ShapefileReader reader = new ShapefileReader(null);
            Envelope envelope = reader.wgs84Bounds();
            checkWgsEnvelopeSize(envelope);
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
