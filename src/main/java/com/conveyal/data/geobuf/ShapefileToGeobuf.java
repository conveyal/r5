package com.conveyal.data.geobuf;

import org.geotools.data.FileDataStore;
import org.geotools.data.FileDataStoreFinder;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.referencing.CRS;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Convert a Shapefile to Geobuf format.
 */
public class ShapefileToGeobuf {
    public static void main (String... args) throws Exception {
        File inShp = new File(args[0]);
        File outGb = new File(args[1]);

        GeobufEncoder encoder = new GeobufEncoder(new FileOutputStream(outGb), 5);

        FileDataStore store = FileDataStoreFinder.getDataStore(inShp);
        SimpleFeatureSource src = store.getFeatureSource();

        Query q = new Query();
        q.setCoordinateSystem(src.getInfo().getCRS());
        q.setCoordinateSystemReproject(CRS.decode("EPSG:4326", true));
        SimpleFeatureCollection sfc = src.getFeatures(q);

        Collection<GeobufFeature> features = new ArrayList<>();

        for (SimpleFeatureIterator it = sfc.features(); it.hasNext();) {
            GeobufFeature feat = new GeobufFeature(it.next());
            features.add(feat);
        }

        encoder.writeFeatureCollection(features);

        encoder.close();
    }
}
