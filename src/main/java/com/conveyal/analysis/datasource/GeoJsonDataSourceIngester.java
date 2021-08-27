package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.analyst.progress.ProgressInputStream;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.data.FeatureReader;
import org.geotools.data.geojson.GeoJSONDataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

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
 * manual reprojection in our ShapefileReader.
 *
 * Allow Attributes to be of "AMBIGUOUS" or null type, or just drop them if they're ambiguous.
 * Flag them as hasMissingValues (or hasNoDataValues).
 */
public class GeoJsonDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    @Override
    public DataSource dataSource () {
        return dataSource;
    }

    public GeoJsonDataSourceIngester () {
        // Note we're using the no-arg constructor creating a totally empty object.
        // Its ID and other general fields will be set later by the enclosing DataSourceUploadAction.
        this.dataSource = new SpatialDataSource();
        dataSource.fileFormat = FileStorageFormat.SHP;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        try {
//            InputStream inputStream = new ProgressInputStream(
//                    progressListener, new BufferedInputStream(new FileInputStream(file))
//            );
            GeoJSONDataStore dataStore = new GeoJSONDataStore(file);
            FeatureReader<SimpleFeatureType, SimpleFeature> featureReader = dataStore.getFeatureReader();
            while (featureReader.hasNext()) {
                SimpleFeature feature = featureReader.next();
                feature.getFeatureType().getGeometryDescriptor().getType();
            }

            SimpleFeatureSource featureSource = dataStore.getFeatureSource();
            featureSource.getBounds();
            featureSource.getSchema();
            dataStore.getSchema();
            dataStore.getBbox();

            // This loads the whole thing into memory. That should be harmless given our file upload size limits.
            // We could also use streamFeatureCollection, which might accommodate more custom validation anyway.
            // As an example, internally readFeatureCollection just calls streamFeatureCollection.
            // This produces a DefaultFeatureCollection, whose schema is the schema of the first element. That's not
            // quite what we want for GeoJSON. So again, we might be better off defining our own streaming logic.
            // By streaming over the features, we avoid loading them all into memory but imitate a lot of the logic
            // in DefaultFeatureCollection.
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection =
                    featureJSON.readFeatureCollection(inputStream);

            // As a GeoJSON file, this is known to be in WGS84. ReferencedEnvelope is a subtype of Envelope.
            ReferencedEnvelope envelope = featureCollection.getBounds();
            checkWgsEnvelopeSize(envelope);

            dataSource.wgsBounds = Bounds.fromWgsEnvelope(envelope);
            dataSource.attributes = ShapefileReader.attributes(featureCollection.getSchema());
            dataSource.geometryType = geometryType(featureCollection);
            dataSource.featureCount = featureCollection.size();
        } catch (Exception e) {
            throw new RuntimeException("Error parsing GeoJSON. Ensure the files you uploaded are valid.", e);
        }
    }
}
