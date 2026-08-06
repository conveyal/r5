package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.OsmDataSource;
import com.conveyal.r5.analyst.progress.ProgressListener;

import java.io.File;

/**
 *
 */
public class OsmDataSourceIngester extends DataSourceIngester {

    private OsmDataSource dataSource;

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    public OsmDataSourceIngester () {
        this.dataSource = new OsmDataSource();
    }

    @Override
    public void ingest(File file, ProgressListener progressListener) {
        // TODO IMPLEMENT
    }

}
