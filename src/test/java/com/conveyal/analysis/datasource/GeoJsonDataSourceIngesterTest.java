package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.SpatialDataSource;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by abyrd on 2021-08-27
 * TODO Instead of loading from files, build GeoJSON programmatically, serialize it to temp files, then load it.
 */
class GeoJsonDataSourceIngesterTest {

    @Test
    void basicValidGeoJson () {
        SpatialDataSource spatialDataSource = ingest("hkzones-wgs84");
    }

    @Test
    void typeMismatch () {
        SpatialDataSource spatialDataSource = ingest("hkzones-type-mismatch");
    }

    @Test
    void extraAttribute () {
        SpatialDataSource spatialDataSource = ingest("hkzones-extra-attribute");
    }

    @Test
    void mixedNumeric () {
        SpatialDataSource spatialDataSource = ingest("hkzones-mixed-numeric");
    }

    @Test
    void mixedGeometries () {
        SpatialDataSource spatialDataSource = ingest("hkzones-mixed-geometries");
    }

    @Test
    void mercatorBadProjection () {
        SpatialDataSource spatialDataSource = ingest("hkzones-mercator");
    }

    // TODO span antimeridian, giant input geometry

    @Test
    void fileEmpty () {
        assertThrows(
                DataSourceException.class,
                () -> ingest("empty"),
                "Expected exception on empty input file."
        );
    }

    @Test
    void fileTooSmall () {
        assertThrows(
                DataSourceException.class,
                () -> ingest("too-small"),
                "Expected exception on input file too short to be GeoJSON."
        );
    }

    /**
     * Test on a GeoJSON file containing huge shapes: the continents of Africa, South America, and Australia.
     */
    @Test
    void continentalScale () {
        Throwable throwable = assertThrows(
                DataSourceException.class,
                () -> ingest("continents"),
                "Expected exception on continental-scale GeoJSON."
        );
        assertTrue(throwable.getMessage().contains("exceeds"));
    }

    /**
     * Test on WGS84 GeoJSON containing shapes on both sides of the 180 degree antimeridian.
     * This case was encountered in the wild: the North Island and the Chatham islands, both part of New Zealand.
     */
    @Test
    void newZealandAntimeridian () {
        Throwable throwable = assertThrows(
                DataSourceException.class,
                () -> ingest("new-zealand-antimeridian"),
                "Expected exception on shapefile crossing antimeridian."
        );
        // TODO generate message specifically about 180 degree meridian, not excessive bbox size
        assertTrue(throwable.getMessage().contains("exceeds"));
    }

    private SpatialDataSource ingest (String inputFile) {
        TestingProgressListener progressListener = new TestingProgressListener();
        DataSourceIngester ingester = DataSourceIngester.forFormat(GEOJSON);
        File geoJsonInputFile = getResourceAsFile(inputFile + ".geojson");
        ingester.ingest(geoJsonInputFile, progressListener);
        // TODO progressListener.assertUsedCorrectly();
        return ((SpatialDataSource) ingester.dataSource());
    }

    // Method is non-static since resource resolution is relative to the package of the current class.
    // In a static context, you can also do XYZTest.class.getResource().
    private File getResourceAsFile (String resource) {
        // This just removes the protocol and query parameter part of the URL, which for File URLs is a file path.
        return new File(getClass().getResource(resource).getFile());
    }

}
