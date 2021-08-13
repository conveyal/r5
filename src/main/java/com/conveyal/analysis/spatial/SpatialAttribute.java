package com.conveyal.analysis.spatial;

import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.type.AttributeType;

/**
 * In OpenGIS terminology, SpatialResources contain features, each of which has attributes. This class represents a
 * single attribute present on all the features in a resource - it's basically the schema metadata for a GIS layer.
 * Users can specify their own name for any attribute in the source file, so this also associates these user-specified
 * names with the original attribute name.
 */
public class SpatialAttribute {

    /** The name of the attribute (CSV column, Shapefile attribute, etc.) in the uploaded source file. */
    public String name;

    /** The editable label specified by the end user. */
    public String label;

    /** The data type of the attribute - for our purposes primarily distinguishing between numbers and text. */
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

    /** No-arg constructor required for Mongo POJO deserialization. */
    public SpatialAttribute () { }

}
