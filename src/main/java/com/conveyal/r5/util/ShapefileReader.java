package com.conveyal.r5.util;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Encapsulate Shapefile reading logic
 */
public class ShapefileReader {
    private final FeatureCollection<SimpleFeatureType, SimpleFeature> features;
    private final DataStore store;
    private final FeatureSource<SimpleFeatureType, SimpleFeature> source;
    private final CoordinateReferenceSystem crs;
    private final MathTransform transform;

    public ShapefileReader (File shapefile) throws IOException, FactoryException {
        // http://docs.geotools.org/stable/userguide/library/data/shape.html
        Map<String, Object> params = new HashMap();
        params.put("url", shapefile.toURI().toURL());

        store = DataStoreFinder.getDataStore(params);
        String typeName = store.getTypeNames()[0];
        source = store.getFeatureSource(typeName);
        Filter filter = Filter.INCLUDE;

        features = source.getFeatures(filter);
        crs = features.getSchema().getCoordinateReferenceSystem();
        transform = CRS.findMathTransform(crs, DefaultGeographicCRS.WGS84, true);
    }

    public Stream<SimpleFeature> stream () throws IOException {
        Iterator<SimpleFeature> wrappedIterator = new Iterator<SimpleFeature>() {
            FeatureIterator<SimpleFeature> wrapped = features.features();

            @Override
            public boolean hasNext() {
                return wrapped.hasNext();
            }

            @Override
            public SimpleFeature next() {
                return wrapped.next();
            }
        };

        return StreamSupport.stream(Spliterators.spliterator(wrappedIterator, features.size(), Spliterator.SIZED), false);
    }

    public ReferencedEnvelope getBounds () throws IOException {
        return source.getBounds();
    }

    public Stream<SimpleFeature> wgs84Stream () throws IOException, TransformException {
        return stream().map(f -> {
            Geometry g = (Geometry) f.getDefaultGeometry();
            try {
                // TODO does this leak beyond this function?
                f.setDefaultGeometry(JTS.transform(g, transform));
            } catch (TransformException e) {
                throw new RuntimeException(e);
            }
            return f;
        });
    }

    public Envelope wgs84Bounds () throws IOException, TransformException {
        return JTS.transform(getBounds(), transform);
    }


    public void close () {
        store.dispose();
    }

    public int getFeatureCount() throws IOException {
        return source.getCount(Query.ALL);
    }
}
