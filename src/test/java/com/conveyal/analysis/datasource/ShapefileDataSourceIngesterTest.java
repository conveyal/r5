package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.SpatialDataSource;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.conveyal.analysis.datasource.SpatialDataSourceIngesterTest.ingest;
import static com.conveyal.file.FileStorageFormat.SHP;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beyond the standard cases in SpatialDataSourceIngesterTest, special cases for ESRI Shapefile ingestion.
 * Test that we can correctly read Shapefiles with many different characteristics, and can detect problematic inputs
 * including ones we've encountered in practice.
 */
class ShapefileDataSourceIngesterTest {

    /**
     * Test on a shapefile that has mostly geometries of type "point" but some geometries of type "null", which is to
     * say that some of the records in the shapefile have missing geometries. The GeoTools shapefile reader will not
     * tolerate geometries of mixed types, but will tolerate inputs like this silently. We want to detect and refuse.
     */
    @Test
    void nullPointGeometries () {
        Throwable throwable = assertThrows(
                DataSourceException.class,
                () -> ingest(SHP, "nl-null-points"),
                "Expected exception on shapefile with null geometries."
        );
        assertTrue(throwable.getMessage().contains("missing"));
    }

    /**
     * Test on a shapefile that has two attributes with the same name (one text and one integer). This is actually
     * possible and has been encountered in the wild, probably due to names being truncated to a fixed length (the
     * DBF file used in shapefiles allows field names of at most ten characters). Apparently this has been "fixed" in
     * Geotools which now silently renames one of the columns with a numeric suffix. It would be preferable to just
     * refuse this kind of input, since the fields are likely to have different names when opened in different software.
     */
    @Test
    void duplicateAttributeNames () {
        SpatialDataSource spatialDataSource = ingest(SHP, "duplicate-fields");
        // id, the_geom, DDDDDDDDDD, and DDDDDDDDDD. The final one will be renamed on the fly to DDDDDDDDDD1.
        assertTrue(spatialDataSource.attributes.size() == 4);
        assertTrue(spatialDataSource.attributes.get(3).name.endsWith("1"));
    }

}