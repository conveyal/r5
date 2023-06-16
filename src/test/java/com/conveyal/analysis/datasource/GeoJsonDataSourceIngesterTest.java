package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.SpatialDataSource;
import org.junit.jupiter.api.Test;

import static com.conveyal.analysis.datasource.SpatialDataSourceIngesterTest.assertIngestException;
import static com.conveyal.analysis.datasource.SpatialDataSourceIngesterTest.testIngest;
import static com.conveyal.analysis.models.DataSourceValidationIssue.Level.ERROR;
import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beyond the standard cases in SpatialDataSourceIngesterTest, special cases for GeoJSON ingestion.
 * TODO Maybe instead of loading from files, build GeoJSON programmatically, serialize it to temp files, then load it.
 */
class GeoJsonDataSourceIngesterTest {

    @Test
    void typeMismatch () {
        SpatialDataSource spatialDataSource = testIngest(GEOJSON, "hkzones-type-mismatch");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void extraAttribute () {
        SpatialDataSource spatialDataSource = testIngest(GEOJSON, "hkzones-extra-attribute");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void mixedNumeric () {
        SpatialDataSource spatialDataSource = testIngest(GEOJSON, "hkzones-mixed-numeric");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void mixedGeometries () {
        SpatialDataSource spatialDataSource = testIngest(GEOJSON, "hkzones-mixed-geometries");
        // Inconsistent geometry between features is detected.
        assertTrue(spatialDataSource.issues.stream().anyMatch(i -> i.level == ERROR));
    }

    @Test
    void fileEmpty () {
        assertIngestException(GEOJSON, "empty", DataSourceException.class, "length");
    }

    @Test
    void fileTooSmall () {
        assertIngestException(GEOJSON, "too-small", DataSourceException.class, "length");
    }

}
