package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.r5.analyst.progress.ProgressListener;

import java.io.File;

/**
 * GoeTIFFs are used as inputs in network building as digital elevation profiles, and eventually expected to
 * serve as impedance or cost fields (e.g. shade bonus and pollution malus).
 */
public class GeoTiffDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    @Override
    protected DataSource dataSource () {
        return dataSource;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        throw new UnsupportedOperationException();
    }

    public GeoTiffDataSourceIngester () {
        this.dataSource = new SpatialDataSource();
    }

}
