package com.conveyal.analysis.datasource;

import com.conveyal.r5.analyst.progress.NoopProgressListener;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by abyrd on 2021-08-27
 * TODO Instead of loading from files, build GeoJSON programmatically, serialize it to temp files, then load it.
 */
class GeoJsonDataSourceIngesterTest {

    String[] inputs = new String[] {
//            "/Users/abyrd/geodata/test-ingest/hkzones-too-small.geojson",
//            "/Users/abyrd/geodata/test-ingest/hkzones-empty.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-type-mismatch.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-extra-attribute.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-mixed-numeric.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-mixed-geometries.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-mercator.geojson",
            "/Users/abyrd/geodata/test-ingest/hkzones-wgs84.geojson",
    };

    @Test
    void testGeoJsonProcessing () {
        for (String input : inputs) {
            TestingProgressListener progressListener = new TestingProgressListener();
            DataSourceIngester ingester = DataSourceIngester.forFormat(GEOJSON);
            File geoJsonInputFile = new File(input);
            ingester.ingest(geoJsonInputFile, progressListener);
            ingester.toString();
            // TODO progressListener.assertUsedCorrectly();
        }
    }

}