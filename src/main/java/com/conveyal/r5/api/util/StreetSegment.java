package com.conveyal.r5.api.util;

import java.util.List;

/**
 * A response object describing a non-transit part of an Option. This is either an access/egress leg of a transit
 * trip, or a direct path to the destination that does not use transit.
 */
public class StreetSegment {
    //Which mode of transport is used @notnull
    public LegMode mode;
    //Time in seconds for this part of trip @notnull
    public int time;
    public List<StreetEdgeInfo> streetEdges;
    //List of elevation elements each elevation has a distance (from start of this segment) and elevation at this point (in meters)
    public List<Elevation> elevation;
    public List<Alert> alerts;

    @Override public String toString() {
        return "\tStreetSegment{" +
            "mode='" + mode + '\'' +
            ", time=" + time +
            ", streetEdges=" + streetEdges.size() +
            '}' + "\n";
    }
}
