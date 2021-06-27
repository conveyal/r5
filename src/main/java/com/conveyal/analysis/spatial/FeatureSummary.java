package com.conveyal.analysis.spatial;

import org.geotools.feature.FeatureCollection;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

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
