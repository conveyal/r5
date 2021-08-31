package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSourceValidationIssue;
import com.conveyal.analysis.models.SpatialDataSource;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.conveyal.analysis.datasource.SpatialDataSourceIngesterTest.ingest;
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
        SpatialDataSource spatialDataSource = ingest(GEOJSON, "hkzones-type-mismatch");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void extraAttribute () {
        SpatialDataSource spatialDataSource = ingest(GEOJSON, "hkzones-extra-attribute");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void mixedNumeric () {
        SpatialDataSource spatialDataSource = ingest(GEOJSON, "hkzones-mixed-numeric");
        // Currently we can't detect problems with inconsistent schema across features.
        // GeoTools seems to report the same schema on every feature.
        assertTrue(spatialDataSource.issues.isEmpty());
    }

    @Test
    void mixedGeometries () {
        SpatialDataSource spatialDataSource = ingest(GEOJSON, "hkzones-mixed-geometries");
        // Inconsistent geometry between features is detected.
        assertTrue(spatialDataSource.issues.stream().anyMatch(i -> i.level == ERROR));
    }

    @Test
    void fileEmpty () {
        assertThrows(
                DataSourceException.class,
                () -> ingest(GEOJSON, "empty"),
                "Expected exception on empty input file."
        );
    }

    @Test
    void fileTooSmall () {
        assertThrows(
                DataSourceException.class,
                () -> ingest(GEOJSON, "too-small"),
                "Expected exception on input file too short to be GeoJSON."
        );
    }

}
