package com.conveyal.analysis.spatial;

import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.type.AttributeType;

/** Groups the original names and user-friendly fields from shapefile attributes, CSV columns, etc. */
public class SpatialAttribute {
    /** Name in source file */
    public String name;

    /** Editable by end users */
    public String label;

    public Type type;

    private enum Type {
        NUMBER, // internally, we generally parse as doubles
        TEXT,
        GEOM,
        ERROR
    }

    public SpatialAttribute(String name, AttributeType type) {
        this.name = name;
        this.label = name;
        if (Number.class.isAssignableFrom(type.getBinding())) this.type = Type.NUMBER;
        else if (String.class.isAssignableFrom(type.getBinding())) this.type = Type.TEXT;
        else if (Geometry.class.isAssignableFrom(type.getBinding())) this.type = Type.GEOM;
        else this.type = Type.ERROR;
    }

    /**
     * No-arg constructor required for Mongo POJO serialization
     */
    public SpatialAttribute () {

    }

}
