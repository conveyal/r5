package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSource;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.r5.common.GeometryUtils.geometryFactory;
import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * DataSource and SpatialDataSource are data model objects used to structure metadata in the database and HTTP API.
 * They don't hold references to components like FileStorage, so should not have attached methods to retrieve and
 * visualize files. It is more conventional for an HttpController or Component to hold a reference to the FileStorage
 * component, which it supplies to this class to generate a preview from a DataSource.
 */
public class DataSourcePreviewGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static final int MAX_FEATURES_IN_PREVIEW = 1000;

    private FileStorage fileStorage;

    public DataSourcePreviewGenerator (FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * There may be some advantage to supplying a full FeatureCollection to the GeoJsonWriter instead of individual
     * features. To be explored.
     */
    public SimpleFeatureCollection wgsPreviewFeatureCollection (DataSource dataSource) {
        throw new UnsupportedOperationException();
    }

    /**
     * Return a list of GeoTools features that will serve as a preview for display on a map. The number of features
     * not exceed 1000. The CRS should be set on these features' geometries, as they will all automatically be projected
     * into longitude-first WGS84 when written to GeoJson by the GeoJsonWriter.
     */
    public List<SimpleFeature> previewFeatures (DataSource dataSource) {
        if (dataSource.fileFormat == FileStorageFormat.SHP) {
            File file = fileStorage.getFile(dataSource.fileStorageKey());
            try {
                ShapefileReader reader = new ShapefileReader(file);
                // Stream will not be exhausted due to limit(), make sure underlying iterator is closed.
                // If this is modified to return a stream, the caller will need to (auto)close the stream.
                List<SimpleFeature> features;
                try (Stream<SimpleFeature> wgsStream = reader.stream()) {
                    features = wgsStream.limit(MAX_FEATURES_IN_PREVIEW).collect(Collectors.toList());
                }
                // Also close the store/file to release lock, but only after the stream and iterator are closed.
                reader.close();
                return features;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            List<Geometry> geometries = wgsPreviewGeometries(dataSource);
            return previewFeaturesFromWgsGeometries(geometries);
        }
    }

    /**
     * As a simplification when generating previews for some kinds of DataSources, return JTS Geometries rather than
     * complete GeoTools features. These Geometries will be converted to features for serialization to GeoJSON.
     * The userData on the first geometry in the list will determine the attribute schema. This userData must be null
     * or a Map<String, Object>. The user data on all subsequent features must have the same fields and types.
     * All geometries in the list must be of the same type. They must all be in longitude-first WGS84, as JTS
     * geometries do not have rich CRS data associated with them and cannot be automatically reprojected by GeoTools
     * when it writes GeoJSON.
     * @return wgs84 geometries with a map of attributes in userData, which can be converted to GeoTools features.
     */
    private List<Geometry> wgsPreviewGeometries (DataSource dataSource) {
        if (dataSource.fileFormat == FileStorageFormat.GEOTIFF) {
            try {
                // FIXME this duplicates a lot of code in RasterDataSourceSampler, raster reading should be factored out.
                // This is crazy verbose considering that we're taking an Envelope with CRS from a GeoTools object
                // and have the final objective of turning it into a GeoTools feature, but we're passing through JTS
                // where we lose the CRS information.
                File localRasterFile = fileStorage.getFile(dataSource.fileStorageKey());
                AbstractGridFormat format = GridFormatFinder.findFormat(localRasterFile);
                // Only relevant for certain files with WGS CRS?
                Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
                GridCoverage2DReader coverageReader = format.getReader(localRasterFile, hints);
                GridCoverage2D coverage = coverageReader.read(null);
                // Set CRS transform from WGS84 to coverage, if any.
                CoordinateReferenceSystem crs = coverage.getCoordinateReferenceSystem2D();
                MathTransform coverageToWgs = CRS.findMathTransform(crs, DefaultGeographicCRS.WGS84);
                Polygon projectedPolygon = JTS.toPolygon(coverage.getEnvelope2D());
                Geometry wgsGeometry = JTS.transform(projectedPolygon, coverageToWgs);
                wgsGeometry.setUserData(Map.of("name", dataSource.name));
                return List.of(wgsGeometry);
            } catch (Exception e) {
                throw new RuntimeException("Exception reading raster:", e);
            }
        }
        return defaultWgsPreviewGeometries(dataSource);
    }

    /**
     * Fallback preview - return a single geometry, which is the bounding box of the DataSource.
     * These may not be tight geographic bounds on the data set, because many CRS are not axis-aligned with WGS84.
     */
    private static List<Geometry> defaultWgsPreviewGeometries (DataSource dataSource) {
        Geometry geometry = geometryFactory.toGeometry(dataSource.wgsBounds.envelope());
        geometry.setUserData(Map.of(
                "name", dataSource.name,
                "id", dataSource._id.toString()
        ));
        return List.of(geometry);
    }

    /**
     * Convert a list of JTS geometries into features for display, following rules explained on previewGeometries().
     * TODO convert everything to streams instead of buffering into lists. Requires peeking at the first geometry.
     */
    private static List<SimpleFeature> previewFeaturesFromWgsGeometries (List<? extends Geometry> geometries) {
        // GeoTools now has support for GeoJson but it appears to still be in flux.
        // GeoJsonDataStore seems to be only for reading.
        // DataUtilities.createType uses a spec entirely expressed as a String which is not ideal.
        // Also, annoyingly, when using this method you can only force XY axis order with a global system property.
        // https://docs.geotools.org/latest/userguide/library/referencing/order.html explains the situation and
        // advises us to always create CRS programmatically, not with Strings. This can take you down a rabbit hole of
        // using chains of verbose constructors and builders, but in fact the SimpleFeatureTypeBuilder has a
        // strightforward add(String, Class) method which will respect the specified CRS when adding geometry types.
        try {
            // Check inputs and extract the first (exemplar) geometry to establish attribute schema.
            checkArgument(notNullOrEmpty(geometries));
            Geometry exemplarGeometry = geometries.get(0);
            final SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
            typeBuilder.setName("DataSource Preview");
            typeBuilder.setCRS(DefaultGeographicCRS.WGS84); // This constant has (lon, lat) axis order used by R5.
            typeBuilder.setDefaultGeometry("the_geom");
            // Attributes are ordered in GeoTools, and can be set later by index number or add() call order.
            // Call add() or addAll() in predetermined order. It's possible to set attributes out of order with set().
            typeBuilder.add(typeBuilder.getDefaultGeometry(), Polygon.class);
            if (exemplarGeometry.getUserData() != null) {
                ((Map<String, Object>) exemplarGeometry.getUserData()).forEach((k, v) -> {
                    typeBuilder.add(k, v.getClass());
                });
            };
            final SimpleFeatureType featureType = typeBuilder.buildFeatureType();
            final SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
            final AtomicInteger featureNumber = new AtomicInteger(0);
            return geometries.stream().map(geometry -> {
                featureBuilder.add(geometry);
                if (geometry.getUserData() != null) {
                    ((Map<String, Object>) geometry.getUserData()).forEach((k, v) -> {
                        featureBuilder.set(k, v);
                    });
                };
                SimpleFeature feature = featureBuilder.buildFeature(Integer.toString(featureNumber.getAndIncrement()));
                return feature;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert geometries to features.", e);
        }
    }

}
