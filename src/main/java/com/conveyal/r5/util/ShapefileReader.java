package com.conveyal.r5.util;

import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.analysis.datasource.SpatialAttribute;
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
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.AttributeType;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Encapsulate Shapefile reading logic
 */
public class ShapefileReader {
    private final FeatureCollection<SimpleFeatureType, SimpleFeature> features;
    private final DataStore store;
    private final FeatureSource<SimpleFeatureType, SimpleFeature> source;
    public final CoordinateReferenceSystem crs;
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

        if (crs == null) {
            throw new IllegalArgumentException("Unrecognized shapefile projection");
        }

        transform = CRS.findMathTransform(crs, DefaultGeographicCRS.WGS84, true);
    }

    public Stream<SimpleFeature> stream () throws IOException {
        Iterator<SimpleFeature> wrappedIterator = new Iterator<SimpleFeature>() {
            FeatureIterator<SimpleFeature> wrapped = features.features();

            @Override
            public boolean hasNext () {
                boolean hasNext = wrapped.hasNext();
                if (!hasNext) {
                    // Prevent keeping a lock on the shapefile.
                    // This doesn't help though when iteration is not completed. Ideally we need to keep a set of any
                    // open iterators and close them all in the close method on the ShapefileReader.
                    wrapped.close();
                }
                return hasNext;
            }

            @Override
            public SimpleFeature next () {
                return wrapped.next();
            }
        };

        return StreamSupport.stream(Spliterators.spliterator(wrappedIterator, features.size(), Spliterator.SIZED), false);
    }

    public ReferencedEnvelope getBounds () throws IOException {
        return source.getBounds();
    }

    public List<String> attributesAssignableTo (Class<?> theClass) {
        return features.getSchema()
                .getAttributeDescriptors()
                .stream()
                .filter(d -> theClass.isAssignableFrom(d.getType().getBinding()))
                .map(AttributeDescriptor::getLocalName)
                .collect(Collectors.toList());
    }

    public List<String> numericAttributes () {
        return attributesAssignableTo(Number.class);
    }

    /**
     * Find an attribute by name _ignoring case_ and ensure it is assignable to variables of a particular class.
     * @return the index of this attribute, so it can be fetched on each feature by index instead of name.
     */
    public int findAttribute (String name, Class<?> assignableTo) {
        SimpleFeatureType featureSchema = features.getSchema();
        int nAttributes = featureSchema.getAttributeCount();
        for (int i = 0; i < nAttributes; i++) {
            AttributeDescriptor descriptor = featureSchema.getDescriptor(i);
            if (descriptor.getName().getLocalPart().equalsIgnoreCase(name) &&
                assignableTo.isAssignableFrom(descriptor.getType().getBinding())) {
                return i;
            }
        }
        throw new IllegalArgumentException("Could not find attribute with specified name and type.");
    }

    public double getAreaSqKm () throws IOException, TransformException, FactoryException {
        CoordinateReferenceSystem webMercatorCRS = CRS.decode("EPSG:3857");
        MathTransform webMercatorTransform = CRS.findMathTransform(crs, webMercatorCRS, true);
        Envelope mercatorEnvelope = JTS.transform(getBounds(), webMercatorTransform);
        return mercatorEnvelope.getArea() / 1000 / 1000;
    }

    public Stream<SimpleFeature> wgs84Stream () throws IOException, TransformException {
        return stream().map(f -> {
            org.locationtech.jts.geom.Geometry g = (org.locationtech.jts.geom.Geometry) f.getDefaultGeometry();
            if (g == null) {
                throw new DataSourceException("Null (missing) geometry on feature: " + f.getID());
            }
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

    /**
     * Failure to call this will leave the shapefile locked, which may mess with future attempts to use it.
     */
    public void close () {
        // Note that you also have to close the iterator, see iterator wrapper code above.
        store.dispose();
    }

    public int featureCount () throws IOException {
        return source.getCount(Query.ALL);
    }

    public List<SpatialAttribute> attributes () {
        return attributes(features.getSchema());
    }

    /** Static utility method for reuse in other classes importing GeoTools FeatureCollections. */
    public static List<SpatialAttribute> attributes (SimpleFeatureType schema) {
        List<SpatialAttribute> attributes = new ArrayList<>();
        HashSet<String> uniqueAttributes = new HashSet<>();
        schema.getAttributeDescriptors().forEach(d -> {
            String attributeName = d.getLocalName();
            AttributeType type = d.getType();
            if (type != null) {
                attributes.add(new SpatialAttribute(attributeName, type));
                uniqueAttributes.add(attributeName);
            }
        });
        if (attributes.size() != uniqueAttributes.size()) {
            throw new DataSourceException("Spatial layer has attributes with duplicate names.");
        }
        return attributes;
    }

    /** These are very broad. For example, line includes linestring and multilinestring. */
    public enum GeometryType {
        POLYGON, POINT, LINE, PIXEL;
        public static GeometryType forBindingClass (Class binding) {
            if (Polygonal.class.isAssignableFrom(binding)) return POLYGON;
            if (Puntal.class.isAssignableFrom(binding)) return POINT;
            if (Lineal.class.isAssignableFrom(binding)) return LINE;
            throw new IllegalArgumentException("Could not determine geometry type of features in DataSource.");
        }
    }

    public GeometryType geometryType () {
        return geometryType(features);
    }

    /** Static utility method for reuse in other classes importing GeoTools FeatureCollections. */
    public static GeometryType geometryType (FeatureCollection featureCollection) {
        Class geometryClass = featureCollection.getSchema().getGeometryDescriptor().getType().getBinding();
        return GeometryType.forBindingClass(geometryClass);
    }

}
