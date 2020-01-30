package com.conveyal.r5.api.util;

import com.conveyal.r5.model.json_serialization.LineStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.locationtech.jts.geom.LineString;

/**
 * Created by mabu on 30.10.2015.
 */
public class PolylineGeometry {

    /**
     * Polyline encoded geometry
     * @notnull
     */
    @JsonDeserialize(using = LineStringDeserializer.class)
    public LineString points;

    /**
     * Length of polyline encoded geometry
     * @notnull
     */
    public int length;
}
