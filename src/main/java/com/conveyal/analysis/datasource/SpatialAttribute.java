package com.conveyal.analysis.datasource;

import org.locationtech.jts.geom.Geometry;
import org.opengis.coverage.SampleDimensionType;
import org.opengis.feature.type.AttributeDescriptor;
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

    /** On how many features does this attribute occur? */
    public int occurrances = 0;

    public enum Type {
        NUMBER, // internally, we generally work with doubles so all numeric GIS types can be up-converted
        TEXT,
        GEOM,
        ERROR;
        public static Type forBindingClass (Class binding) {
            if (Number.class.isAssignableFrom(binding)) return Type.NUMBER;
            else if (String.class.isAssignableFrom(binding)) return Type.TEXT;
            else if (Geometry.class.isAssignableFrom(binding)) return Type.GEOM;
            else return Type.ERROR;
        }
    }

    /**
     * Given an OpenGIS AttributeType, create a new Conveyal attribute metadata object reflecting it.
     */
    public SpatialAttribute(String name, AttributeType type) {
        this.name = name;
        this.label = name;
        this.type = Type.forBindingClass(type.getBinding());
    }

    public SpatialAttribute (AttributeDescriptor descriptor) {
        this(descriptor.getLocalName(), descriptor.getType());
    }

    public SpatialAttribute (SampleDimensionType dimensionType, int bandNumber) {
        name = "Band " + bandNumber;
        label = String.format("%s (%s)", name, dimensionType.name());
        type = Type.NUMBER;
    }

    /** No-arg constructor required for Mongo POJO deserialization. */
    public SpatialAttribute () { }

}
