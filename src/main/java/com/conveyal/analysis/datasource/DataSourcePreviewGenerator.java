package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.DataSource;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.r5.common.GeometryUtils.geometryFactory;
import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkArgument;

public class DataSourcePreviewGenerator {

    private FileStorage fileStorage;

    public DataSourcePreviewGenerator (FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    public SimpleFeatureCollection wgsPreviewFeatureCollection (DataSource dataSource) {
        return null;
    }

    // DataSource and SpatialDataSource are data model objects used to structure metadata in the database.
    // They don't hold references to file storage components so should not have attached methods to retrieve files.
    // It is more conventional for an HttpController or Component to hold a reference to the FileStorage component.
    /**
     * Return a list of JTS Geometries in WGS84 longitude-first CRS. These will serve as a preview for display on a
     * map. The number of features should be less than 1000. The userData on the first geometry in the list will
     * determine the schema. It must be null or a Map<String, Object>. The user data on all subsequent features must
     * have the same fields and types. All geometries in the list must be of the same type.
     * Nonstatic just to get access to the fileStorage (should preview generation be a separate component?)
     */
    public List<SimpleFeature> wgsPreviewFeatures (DataSource dataSource) {
        if (dataSource.fileFormat == FileStorageFormat.SHP) {
            File file = fileStorage.getFile(dataSource.fileStorageKey());
            try {
                ShapefileReader reader = new ShapefileReader(file);
                // Stream will not be exhausted due to limit(), make sure underlying iterator is closed.
                // If this is modified to return a stream, the caller will need to (auto)close the stream.
                List<SimpleFeature> features;
                try (Stream<SimpleFeature> wgsStream = reader.wgs84Stream()) {
                    features = wgsStream.limit(1000).collect(Collectors.toList());
                }
                // Also close the store/file to release lock, but only after the stream and iterator are closed.
                reader.close();
                return features;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            List<Geometry> geometries = wgsPreviewGeometries(dataSource);
            return wgsPreviewFeaturesFromGeometries(geometries);
        }
    }

    // return geometries with userData rather than features, then convert them.
    private static List<Geometry> wgsPreviewGeometries (DataSource dataSource) {
        return defaultWgsPreviewGeometries(dataSource);
    }

    // Fallback preview - return a single geometry, which is the bounding box of the DataSource.
    // These may not be tight geographic bounds on the data set, because many CRS are not axis-aligned with WGS84.
    private static List<Geometry> defaultWgsPreviewGeometries (DataSource dataSource) {
        Geometry geometry = geometryFactory.toGeometry(dataSource.wgsBounds.envelope());
        geometry.setUserData(Map.of(
                "name", dataSource.name,
                "id", dataSource._id.toString()
        ));
        return List.of(geometry);
    }

    // TODO convert everything to streams instead of buffering into lists. This will requre peeking at the first geometry.
    private static List<SimpleFeature> wgsPreviewFeaturesFromGeometries (List<? extends Geometry> geometries) {
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
