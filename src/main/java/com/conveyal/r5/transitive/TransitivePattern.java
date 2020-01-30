package com.conveyal.r5.transitive;

import com.conveyal.r5.util.EncodedPolylineSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.locationtech.jts.geom.LineString;

import java.util.List;

/**
 * Represents a transitive pattern.
 */
public class TransitivePattern {
    public String pattern_id;
    public String pattern_name;
    public String route_id;
    public List<StopIdRef> stops;

    // TODO is this level of indirection necessary?
    public static class StopIdRef {
        /** NB this is the stop index in the R5 graph, not the GTFS stop ID */
        public String stop_id;

        @JsonSerialize(using = EncodedPolylineSerializer.class)
        public LineString geometry;

        public StopIdRef (String stop_id, LineString geometry) {
            this.stop_id = stop_id;
            this.geometry = geometry;
        }
    }
}
