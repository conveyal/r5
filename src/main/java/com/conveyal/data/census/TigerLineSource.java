package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufFeature;
import org.geotools.data.FileDataStore;
import org.geotools.data.FileDataStoreFinder;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.referencing.CRS;

import java.io.File;
import java.util.HashMap;

/**
 * Reads TIGER/Line data into a MapDB.
 */
public class TigerLineSource {
    private File shapefile;

    public TigerLineSource (File shapefile) {
        this.shapefile = shapefile;
    }

    public void load (ShapeDataStore store) throws Exception {
        FileDataStore fds = FileDataStoreFinder.getDataStore(shapefile);
        SimpleFeatureSource src = fds.getFeatureSource();

        Query q = new Query();
        q.setCoordinateSystem(src.getInfo().getCRS());
        q.setCoordinateSystemReproject(CRS.decode("EPSG:4326", true));
        SimpleFeatureCollection sfc = src.getFeatures(q);

        for (SimpleFeatureIterator it = sfc.features(); it.hasNext();) {
            GeobufFeature feat = new GeobufFeature(it.next());
            feat.id = null;
            feat.numericId = Long.parseLong((String) feat.properties.get("GEOID10"));
            feat.properties = new HashMap<>();
            store.add(feat);
        }
    }
}
