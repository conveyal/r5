package com.conveyal.analysis.spatial;

import org.geotools.feature.FeatureCollection;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

/**
 * Records the geometry type (polygon, point, or line) and number of features in a particular SpatialResource.
 * Types are very general, so line type includes things like multilinestrings which must be unwrapped.
 * Could we just flatten this into SpatialResource?
 */
public class FeatureSummary {
    public int count;
    public Type type;

    public enum Type {
        POLYGON,
        POINT,
        LINE;
    }

    public FeatureSummary (FeatureCollection<SimpleFeatureType, SimpleFeature> features) {
        Class geometryType = features.getSchema().getGeometryDescriptor().getType().getBinding();
        if (Polygonal.class.isAssignableFrom(geometryType)) this.type = Type.POLYGON;
        if (Puntal.class.isAssignableFrom(geometryType)) this.type = Type.POINT;
        if (Lineal.class.isAssignableFrom(geometryType)) this.type = Type.LINE;
        // TODO throw exception if geometryType is not one of the above
        this.count = features.size();
    }

    /**
     * No-arg constructor for Mongo serialization
     */
    public FeatureSummary () {
    }

}
