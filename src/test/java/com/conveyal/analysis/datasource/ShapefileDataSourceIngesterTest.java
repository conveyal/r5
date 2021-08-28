package com.conveyal.analysis.datasource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static com.conveyal.file.FileStorageFormat.SHP;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 *
 */
class ShapefileDataSourceIngesterTest {

    @Test
    void testNullPoints () {
        TestingProgressListener progressListener = new TestingProgressListener();
        DataSourceIngester ingester = DataSourceIngester.forFormat(SHP);
        File inputFile = new File("/Users/abyrd/geodata/test-ingest/nl-null-points.shp");
        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> ingester.ingest(inputFile, progressListener),
                "Expected exception on shapefile with null geometries."
        );
        // TODO progressListener.assertUsedCorrectly();
    }

}