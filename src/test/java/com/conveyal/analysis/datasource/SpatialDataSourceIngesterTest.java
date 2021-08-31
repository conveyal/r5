package com.conveyal.analysis.datasource;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.util.ShapefileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Envelope;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static com.conveyal.analysis.datasource.SpatialAttribute.Type.NUMBER;
import static com.conveyal.analysis.datasource.SpatialAttribute.Type.TEXT;
import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static com.conveyal.file.FileStorageFormat.GEOPACKAGE;
import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.r5.util.ShapefileReader.GeometryType.POLYGON;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test ingestion of all different GeoTools based geographic (spatial) data source files, including GeoPackage.
 */
class SpatialDataSourceIngesterTest {

    // TODO parameter provider method for typed FileStorageFormat enum values instead of String arrays.
    // Or a separate local enum that gets mapped to the FileStorageFormat enum.

    /** Envelope around Hong Kong Island, Kowloon, and Lamma. */
    public static final Envelope HK_ENVELOPE = new Envelope(114.09, 114.40, 22.18, 22.20);

    /**
     * Test on small basic data sets with no errors, but projected into some relatively obscure local coordinate system.
     */
    @ParameterizedTest
    @EnumSource(names = {"GEOPACKAGE", "GEOJSON", "SHP"})
    void basicValid (FileStorageFormat format) {
        // JUnit can't yet do cartesian products of parameters - iterate within this method.
        // According to Github issues, soon we should have List<Arguments> Arguments.cartesianProduct(Set<?>... sets)
        // TODO for (String geomType : List.of("point", "polygon", "linestring")) {
        // For now all test files are polygons with three features and two additional attributes (name and count).
        for (String fileSuffix : List.of("wgs84", "projected")) {
            SpatialDataSource spatialDataSource = ingest(format, "valid-polygon-" + fileSuffix);
            assertTrue(spatialDataSource.issues.isEmpty());
            assertTrue(spatialDataSource.geometryType == POLYGON);
            assertTrue(spatialDataSource.featureCount == 3);
            assertTrue(hasAttribute(spatialDataSource.attributes, "Name", TEXT));
            assertTrue(hasAttribute(spatialDataSource.attributes, "Count", NUMBER));
            assertFalse(hasAttribute(spatialDataSource.attributes, "Count", TEXT));
            // FIXME projected DataSources are returning projected bounds, not WGS84.
            assertTrue(HK_ENVELOPE.contains(spatialDataSource.wgsBounds.envelope()));
        }
    }

    /** Test on files containing huge shapes: the continents of Africa, South America, and Australia. */
    @ParameterizedTest
    @EnumSource(names = {"GEOPACKAGE", "GEOJSON", "SHP"})
    void continentalScale (FileStorageFormat format) {
        Throwable throwable = assertThrows(
                DataSourceException.class,
                () -> ingest(format, "continents"),
                "Expected exception on continental-scale geographic features."
        );
        assertTrue(throwable.getMessage().contains("exceeds"));
    }

    /**
     * Test on projected (non-WGS84) GeoPackage containing shapes on both sides of the 180 degree antimeridian.
     * This case was encountered in the wild: the North Island and the Chatham islands, both part of New Zealand.
     */
    @ParameterizedTest
    @EnumSource(names = {"GEOPACKAGE", "GEOJSON", "SHP"})
    void newZealandAntimeridian (FileStorageFormat format) {
        Throwable throwable = assertThrows(
                DataSourceException.class,
                () -> ingest(format, "new-zealand-antimeridian"),
                "Expected exception on geographic feature collection crossing antimeridian."
        );
        // TODO generate message specifically about 180 degree meridian, not excessive bbox size
        assertTrue(throwable.getMessage().contains("exceeds"));
    }

    public static SpatialDataSource ingest (FileStorageFormat format, String inputFile) {
        TestingProgressListener progressListener = new TestingProgressListener();
        DataSourceIngester ingester = DataSourceIngester.forFormat(format);
        ingester.initializeDataSource("TestName", "Test Description", "test_region_id",
                new UserPermissions("test@email.com", false, "test_group"));
        File geoJsonInputFile = getResourceAsFile(String.join(".", inputFile, format.extension));
        ingester.ingest(geoJsonInputFile, progressListener);
        progressListener.assertUsedCorrectly();
        return ((SpatialDataSource) ingester.dataSource());
    }

    /** Method is static, so resolution is always relative to the package of the class where it's defined. */
    private static File getResourceAsFile (String resource) {
        // This just removes the protocol and query parameter part of the URL, which for File URLs leaves a file path.
        return new File(SpatialDataSourceIngesterTest.class.getResource(resource).getFile());
    }

    protected static boolean hasAttribute (List<SpatialAttribute> attributes, String name, SpatialAttribute.Type type) {
        Optional<SpatialAttribute> optional = attributes.stream().filter(a -> a.name.equals(name)).findFirst();
        return optional.isPresent() && optional.get().type == type;
        // && attribute.occurrances > 0
    }

}
