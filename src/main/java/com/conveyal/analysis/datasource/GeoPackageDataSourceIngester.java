package com.conveyal.analysis.datasource;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.util.ShapefileReader;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.geojson.GeoJSONDataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;
import static com.conveyal.r5.util.ShapefileReader.geometryType;

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
        dataSource.fileFormat = FileStorageFormat.SHP;
    }

    @Override
    public void ingest (File file, ProgressListener progressListener) {
        try {
            Map params = new HashMap();
            params.put("dbtype", "geopkg");
            params.put("database", file.getAbsolutePath());
            DataStore datastore = DataStoreFinder.getDataStore(params);
            // TODO Remaining logic should be similar to Shapefile and GeoJson
        } catch (Exception e) {
            throw new RuntimeException("Error parsing GeoPackage. Ensure the file you uploaded is valid.", e);
        }
    }

}
