package com.conveyal.r5.analyst.scenario;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;

/**
 * This associates a single Polygonal Geometry with a name, numerical data, and a priority relative to other polygons
 * in the same set.
 */
public class ModificationPolygon {

    public final Geometry polygonal;
    public final String id;
    public final String name;
    public final double data;
    public final double priority;

    public ModificationPolygon (
        Polygonal polygonal,
        String id,
        String name,
        double data,
        double priority
    ) {
        this.polygonal = (Geometry) polygonal;
        this.id = id;
        this.name = name;
        this.data = data;
        this.priority = priority;
    }

}

