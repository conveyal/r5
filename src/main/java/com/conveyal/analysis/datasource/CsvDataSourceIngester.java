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
        throw new UnsupportedOperationException();
    }

}
