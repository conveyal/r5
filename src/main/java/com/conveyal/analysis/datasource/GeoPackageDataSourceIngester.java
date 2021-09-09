package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.geojson.GeoJSONDataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;
import static com.conveyal.r5.util.ShapefileReader.attributes;
import static com.conveyal.r5.util.ShapefileReader.geometryType;
import static com.google.common.base.Preconditions.checkState;

/**
 * Logic to create SpatialDataSource metadata from an uploaded GeoPackage file and perform validation.
 * We are using the (unsupported) GeoTools module for loading GeoPackages into OpenGIS Features.
 *
 * Note that a GeoPackage can be a vector or a raster (coverage). We should handle both cases.
 */
public class GeoPackageDataSourceIngester extends DataSourceIngester {

    private final SpatialDataSource dataSource;

    @Override
    public DataSource dataSource () {
        return dataSource;
    }

    public GeoPackageDataSourceIngester () {
        // Note we're using the no-arg constructor creating a totally empty object.
        // Its ID and other general fields will be set later by the enclosing DataSourceUploadAction.
        this.dataSource = new SpatialDataSource();
        dataSource.fileFormat = FileStorageFormat.GEOPACKAGE;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        progressListener.beginTask("Validating uploaded GeoPackage", 2);
        progressListener.setWorkProduct(dataSource.toWorkProduct());
        try {
            Map params = new HashMap();
            params.put("dbtype", "geopkg");
            params.put("database", file.getAbsolutePath());
            DataStore datastore = DataStoreFinder.getDataStore(params);
            // TODO Remaining logic should be similar to Shapefile and GeoJson
            // Some GeoTools DataStores have multiple tables ("type names") available. GeoPackage seems to allow this.
            // Shapefile has only one per DataStore, so the ShapefileDataStore provides a convenience method that does
            // this automatically.
            String[] typeNames = datastore.getTypeNames();
            if (typeNames.length != 1) {
                throw new RuntimeException("GeoPackage must contain only one table, this file has " + typeNames.length);
            }
            FeatureSource featureSource = datastore.getFeatureSource(typeNames[0]);
            Query query = new Query(Query.ALL);
            query.setCoordinateSystemReproject(DefaultGeographicCRS.WGS84);
            FeatureCollection<SimpleFeatureType, SimpleFeature> wgsFeatureCollection = featureSource.getFeatures(query);
            Envelope wgsEnvelope = wgsFeatureCollection.getBounds();
            checkWgsEnvelopeSize(wgsEnvelope);
            progressListener.increment();
            FeatureIterator<SimpleFeature> wgsFeatureIterator = wgsFeatureCollection.features();
            while (wgsFeatureIterator.hasNext()) {
                Geometry wgsFeatureGeometry = (Geometry)(wgsFeatureIterator.next().getDefaultGeometry());
                // FIXME GeoTools seems to be returning an envelope slightly smaller than the projected shapes.
                //  maybe it's giving us projection(envelope(shapes)) instead of envelope(projection(shapes))?
                // As a stopgap, test that they intersect.
                checkState(wgsEnvelope.intersects(wgsFeatureGeometry.getEnvelopeInternal()));
            }
            dataSource.wgsBounds = Bounds.fromWgsEnvelope(wgsEnvelope);
            dataSource.attributes = attributes(wgsFeatureCollection.getSchema());
            dataSource.geometryType = geometryType(wgsFeatureCollection);
            dataSource.featureCount = wgsFeatureCollection.size();
            dataSource.coordinateSystem =
                    featureSource.getSchema().getCoordinateReferenceSystem().getName().getCode();
            progressListener.increment();
        } catch (IOException e) {
            throw new RuntimeException("Error reading GeoPackage.", e);
        }
    }

}
