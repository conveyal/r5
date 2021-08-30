package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.DataSourceValidationIssue;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.analyst.progress.ProgressInputStream;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.data.FeatureReader;
import org.geotools.data.geojson.GeoJSONDataStore;
import org.geotools.data.geojson.GeoJSONFeatureSource;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.AttributeType;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.conveyal.analysis.models.DataSourceValidationIssue.Level.ERROR;
import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;
import static com.conveyal.r5.util.ShapefileReader.geometryType;

/**
 * Logic to create SpatialDataSource metadata from an uploaded GeoJSON file and perform validation.
 * We are using the (unsupported) GeoTools module for loading GeoJSON into a FeatureCollection of OpenGIS Features.
 * However, GeoJSON deviates significantly from usual GIS concepts. In a GeoJSON feature collection,
 * every single object can have a different geometry type and different properties.
 * GeoJSON is always in WGS84, which is also how we handle things internally, so we can avoid any CRS transform logic.
 * GeoJSON geometries are JSON objects with a type property (Point, LineString, Polygon, MultiPoint, MultiPolygon,
 * or MultiLineString) and an array of coordinates. The "multi" types simply have another level of nested arrays.
 * Geometries are usually nested into objects of type "Feature", which allows attaching properties. Features can be
 * further nested into a top-level object of type FeatureCollection. We only support GeoJSON whose top level object is
 * a FeatureCollection (not a single Feature or a single Geometry), and where every geometry is of the same type.
 *
 * Section 4 of the GeoJSON RFC at https://datatracker.ietf.org/doc/html/rfc7946#section-4 defines the only acceptable
 * coordinate reference system as WGS84. You may notice some versions of the GeoTools GeoJSON handler have CRS parsing
 * capabilities. This is just support for an obsolete feature and should not be invoked. We instead range check all
 * incoming coordinates (via a total bounding box check) to ensure they look reasonable in WGS84.
 *
 * Current stable Geotools documentation (version 25?) shows a GeoJSONFeatureSource and CSVFeatureSource.
 * We're using 21.2 which does not have these.
 *
 * In GeoTools FeatureSource is a read-only mechanism but it can apparently only return FeatureCollections, which load
 * everything into memory. FeatureReader provides iterator-style access, but seems quite low-level and not intended
 * for regular use. Because we limit the size of file uploads we can be fairly sure it will be harmless for the backend
 * to load any data fully into memory. Streaming capabilities can be added later if the need arises.
 * This is explained well at: https://docs.geotools.org/stable/userguide/tutorial/datastore/read.html
 * The datastore.getFeatureReader() idiom used in our ShapefileReader class seems to be the right way to stream.
 * But it seems unecessary to go through the steps we do steps - our ShapfileReader creates a FeatureSource and FeatureCollection
 * in memory. Actually we're doing the same thing in ShapefileMain but worse - supplying a query when there is a
 * parameter-less method to call.
 *
 * As of summer 2021, the unsupported module gt-geojson (package org.geotools.geojson) is deprecated and has been
 * replaced with gt-geojsondatastore (package org.geotools.data.geojson), which is on track to supported module status.
 * The newer module uses Jackson instead of an abandoned JSON library, and uses standard GeoTools DataStore interfaces.
 * We also have our own com.conveyal.geojson.GeoJsonModule which should be phased out if GeoTools support is sufficient.
 *
 * They've also got flatbuf and geobuf modules - can we replace our custom one?
 *
 * Note that GeoTools featureReader queries have setCoordinateSystemReproject method - we don't need to do the
 * manual reprojection in our ShapefileReader as we currently are.
 *
 * Allow Attributes to be of "AMBIGUOUS" or null type, or just drop them if they're ambiguous.
 * Flag them as hasMissingValues, or the quantity of missing values.
 *
 * Be careful, QGIS will happily export GeoJSON with a CRS property which is no longer considered valid:
 * "crs": { "type": "name", "properties": { "name": "urn:ogc:def:crs:EPSG::3857" } }
 * If a CRS is present, make sure it matches one of the names for WGS84. Throw a warning if the field is present at all.
 *
 * See also: com.conveyal.r5.analyst.scenario.IndexedPolygonCollection#loadFromS3GeoJson()
 */
public class GeoJsonDataSourceIngester extends DataSourceIngester {

    public static final int MIN_GEOJSON_FILE_LENGTH = "{'type':'GeometryCollection','features':[]}".length();

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
        // Check that file exists and is not empty.
        // Geotools GeoJson reader fails with stack overflow on empty/missing file. TODO: File GeoTools issue.
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
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = featureSource.getFeatures();
            // The schema of the FeatureCollection does seem to reflect all attributes present on all features.
            // However the type of those attributes seems to be restricted to that of the first value encountered.
            // Conversions may fail silently on any successive instances of that property with a different type.
            SimpleFeatureType featureType = featureCollection.getSchema();
            // Note: this somewhat duplicates ShapefileReader.attributes, code should be reusable across formats
            // But look into the null checking and duplicate attribute checks there.
            dataSource.attributes = new ArrayList<>();
            for (AttributeDescriptor descriptor : featureType.getAttributeDescriptors()) {
                dataSource.attributes.add(new SpatialAttribute(descriptor));
            }
            // The schema always reports the geometry type as the very generic "Geometry" class.
            // Check that all features have the same concrete Geometry type.
            Class firstGeometryType = null;
            FeatureIterator<SimpleFeature> iterator = featureCollection.features();
            while (iterator.hasNext()) {
                SimpleFeature feature = iterator.next();
                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                if (geometry == null) {
                    dataSource.addIssue(ERROR, "Geometry is null on feature: " + feature.getID());
                    continue;
                }
                if (firstGeometryType == null) {
                    firstGeometryType = geometry.getClass();
                } else if (firstGeometryType != geometry.getClass()) {
                    dataSource.addIssue(ERROR, "Inconsistent geometry type on feature: " + feature.getID());
                    continue;
                }
            }
            checkCrs(featureType);
            // Set SpatialDataSource fields (Conveyal metadata) from GeoTools model
            ReferencedEnvelope envelope = featureCollection.getBounds();
            // TODO Range-check lats and lons, projection of bad inputs can give negative areas (even check every feature)
            // TODO Also check bounds for antimeridian crossing
            checkWgsEnvelopeSize(envelope);
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(envelope);
            // Cannot set from FeatureType because it's always Geometry for GeoJson.
            dataSource.geometryType = ShapefileReader.GeometryType.forBindingClass(firstGeometryType);
            dataSource.featureCount = featureCollection.size();
        } catch (FactoryException | IOException e) {
            // Unexpected errors cause immediate failure; predictable issues will be recorded on the DataSource object.
            // Catch only checked exceptions to preserve the top-level exception type when possible.
            throw new DataSourceException("Error parsing GeoJSON. Please ensure the files you uploaded are valid.");
        }
    }

    /**
     * GeoJSON used to allow CRS, but the RFC now says GeoJSON is always in WGS84 and no other CRS are allowed.
     * QGIS and GeoTools both seem to support this, but it's an obsolete feature.
     */
    private static void checkCrs (FeatureType featureType) throws FactoryException {
        CoordinateReferenceSystem crs = featureType.getCoordinateReferenceSystem();
        if (crs != null && !DefaultGeographicCRS.WGS84.equals(crs) && !CRS.decode("CRS:84").equals(crs)) {
            throw new DataSourceException("GeoJSON should specify no coordinate reference system, and contain " +
                    "unprojected WGS84 coordinates. CRS is: " + crs.toString());
        }

    }
}
