package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader.GeometryType;
import org.geotools.data.Query;
import org.geotools.data.geojson.GeoJSONDataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.conveyal.analysis.models.DataSourceValidationIssue.Level.ERROR;
import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

/**
 * Logic to create SpatialDataSource metadata from an uploaded GeoJSON file and perform validation.
 *
 * GeoJSON geometries are JSON objects with a type property (Point, LineString, Polygon, MultiPoint, MultiPolygon,
 * or MultiLineString) and an array of coordinates. The "multi" types simply have another level of nested arrays.
 * Geometries are usually nested into objects of type "Feature", which allows attaching properties. Features can be
 * further nested into a top-level object of type FeatureCollection. We only support GeoJSON whose top level object is
 * a FeatureCollection (not a single Feature or a single Geometry), and where every geometry is of the same type.
 *
 * For consistency with other vector data sources and our internal geometry representation, we are using the
 * (unsupported) GeoTools module gt-geojsondatasource for loading GeoJSON into a FeatureCollection of OpenGIS Features.
 *
 * This is somewhat problematic because GeoJSON does not adhere to some common GIS principles. For example, in a
 * GeoJSON feature collection, every single object can have a different geometry type and different properties, or
 * even properties with the same name but different data types. For simplicity we only support GeoJSON inputs that
 * have a consistent schema across all features - the same geometry type and attribute types on every feature. We
 * still need to verify these constraints ourselves, as GeoTools does not enforce them.
 *
 * It is notable that GeoTools will not work correctly with GeoJSON input that does not respect these constraints, but
 * it does not detect or report those problems - it just fails silently. For example, GeoTools will report the same
 * property schema for every feature in a FeatureCollection. If a certain property is reported as having an integer
 * numeric type, but a certain feature has text in the attribute of that same name, the reported value will be an
 * Integer object with a value of zero, not a String.
 *
 * This behavior is odd, but remember that the gt-geojsondatastore module is unsupported (though apparently on the path
 * to being supported) and was only recently included in Geotools releases. We may want to make code contributions to
 * Geotools to improve JSON validation and error reporting.
 *
 * Section 4 of the GeoJSON RFC at https://datatracker.ietf.org/doc/html/rfc7946#section-4 defines the only acceptable
 * coordinate reference system as WGS84. You may notice older versions of the GeoTools GeoJSON handler have CRS parsing
 * capabilities. This is just support for an obsolete feature and should not be invoked. We instead range check all
 * incoming coordinates (via a total bounding box check) to ensure they look reasonable in WGS84.
 *
 * Note that QGIS will happily and silently export GeoJSON with a crs field, which gt-geojsondatastore will happily
 * read and report that it's in WGS84 without ever looking at the crs field. This is another case where Geotools would
 * seriously benefit from added validation and error reporting, and where we need to add stopgap validation of our own.
 *
 * In GeoTools, FeatureSource is a read-only mechanism but it can apparently only return FeatureCollections which load
 * everything into memory. FeatureReader provides iterator-style access, but seems quite low-level and not intended
 * for regular use. Because we limit the size of file uploads we can be fairly sure it will be harmless for the backend
 * to load any data fully into memory. Feature streaming capabilities and/or streaming JSON decoding can be added later
 * if the need arises. The use of FeatureReader and FeatureSource are explained well at:
 * https://docs.geotools.org/stable/userguide/tutorial/datastore/read.html
 */
public class GeoJsonDataSourceIngester extends DataSourceIngester {

    public static final int MIN_GEOJSON_FILE_LENGTH = "{'type':'FeatureCollection','features':[]}".length();

    private final SpatialDataSource dataSource;

    @Override
    public DataSource dataSource () {
        return dataSource;
    }

    public GeoJsonDataSourceIngester () {
        // Note we're using the no-arg constructor creating a totally empty object.
        // Its ID and other general fields will be set later by the enclosing DataSourceUploadAction.
        this.dataSource = new SpatialDataSource();
        dataSource.fileFormat = FileStorageFormat.GEOJSON;
    }


    @Override
    public void ingest (File file, ProgressListener progressListener) {
        progressListener.beginTask("Processing and validating uploaded GeoJSON", 1);
        progressListener.setWorkProduct(dataSource.toWorkProduct());
        // Check that file exists and is not empty. Geotools reader fails with stack overflow on empty/missing file.
        if (!file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + file.getPath());
        }
        if (file.length() < MIN_GEOJSON_FILE_LENGTH) {
            throw new DataSourceException("File is too short to be GeoJSON, length is: " + file.length());
        }
        try {
            // Note that most of this logic is identical to Shapefile and GeoPackage, extract common code.
            GeoJSONDataStore dataStore = new GeoJSONDataStore(file);
            SimpleFeatureSource featureSource = dataStore.getFeatureSource();
            // This loads the whole thing into memory. That should be harmless given our file upload size limits.
            Query query = new Query(Query.ALL);
            query.setCoordinateSystemReproject(DefaultGeographicCRS.WGS84);
            FeatureCollection<SimpleFeatureType, SimpleFeature> wgsFeatureCollection = featureSource.getFeatures(query);
            // The schema of the FeatureCollection does seem to reflect all attributes present on all features.
            // However the type of those attributes seems to be restricted to that of the first value encountered.
            // Conversions may fail silently on any successive instances of that property with a different type.
            SimpleFeatureType featureType = wgsFeatureCollection.getSchema();
            // Note: this somewhat duplicates ShapefileReader.attributes, code should be reusable across formats
            // But look into the null checking and duplicate attribute checks there.
            dataSource.attributes = new ArrayList<>();
            for (AttributeDescriptor descriptor : featureType.getAttributeDescriptors()) {
                dataSource.attributes.add(new SpatialAttribute(descriptor));
            }
            // The schema always reports the geometry type as the very generic "Geometry" class.
            // Check that all features have the same concrete Geometry type.
            Set<Class<?>> geometryClasses = new HashSet<>();
            FeatureIterator<SimpleFeature> iterator = wgsFeatureCollection.features();
            while (iterator.hasNext()) {
                SimpleFeature feature = iterator.next();
                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                if (geometry == null) {
                    dataSource.addIssue(ERROR, "Geometry is null on feature: " + feature.getID());
                    continue;
                }
                geometryClasses.add(geometry.getClass());
            }
            checkCrs(featureType);
            Envelope wgsEnvelope = wgsFeatureCollection.getBounds();
            checkWgsEnvelopeSize(wgsEnvelope);

            // Set SpatialDataSource fields (Conveyal metadata) from GeoTools model
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(wgsEnvelope);
            dataSource.featureCount = wgsFeatureCollection.size();
            // Cannot set geometry type based on FeatureType.getGeometryDescriptor() because it's always just Geometry
            // for GeoJson. We will leave the type null if there are zero or multiple geometry types present.
            List<GeometryType> geometryTypes =
                    geometryClasses.stream().map(GeometryType::forBindingClass).collect(Collectors.toList());
            if (geometryTypes.isEmpty()) {
                dataSource.addIssue(ERROR, "No geometry types are present.");
            } else if (geometryTypes.size() > 1) {
                dataSource.addIssue(ERROR, "Multiple geometry types present: " + geometryTypes);
            } else {
                dataSource.geometryType = geometryTypes.get(0);
            }
            dataSource.coordinateSystem = DefaultGeographicCRS.WGS84.getName().getCode();
            progressListener.increment();
        } catch (FactoryException | IOException e) {
            // Catch only checked exceptions to avoid excessive wrapping of root cause exception when possible.
            throw new DataSourceException("Error parsing GeoJSON. Please ensure the files you uploaded are valid.");
        }
    }

    /**
     * GeoJSON used to allow CRS, but the RFC now says GeoJSON is always in WGS84 and no other CRS are allowed.
     * QGIS and GeoTools both seem to support crs fields, but it's an obsolete feature.
     */
    private static void checkCrs (FeatureType featureType) throws FactoryException {
        // FIXME newer GeoTools always reports WGS84 even when crs field is present.
        //  It doesn't report the problem or attempt any reprojection.
        CoordinateReferenceSystem crs = featureType.getCoordinateReferenceSystem();
        if (crs != null && !DefaultGeographicCRS.WGS84.equals(crs) && !CRS.decode("CRS:84").equals(crs)) {
            throw new DataSourceException("GeoJSON should specify no coordinate reference system, and contain " +
                    "unprojected WGS84 coordinates. CRS is: " + crs.toString());
        }

    }
}
