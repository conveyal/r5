package com.conveyal.r5.streets;

import com.conveyal.osmlib.Way;

/** Map OSM highway=* tags into ints representing a hierarchichy. This helps simplify display at low zoom levels. */
public enum StreetClass {
    MOTORWAY(0), PRIMARY(1), SECONDARY(2), TERTIARY(3), OTHER(4);
    public final byte code;

    StreetClass (int code) {
        this.code = (byte) code;
    }

    public static StreetClass forWay (Way way) {
        String streetClass = way.getTag("highway");
        if ("motorway".equals(streetClass)) {
            return StreetClass.MOTORWAY;
        } else if ("primary".equals(streetClass)) {
            return StreetClass.PRIMARY;
        } else if ("secondary".equals(streetClass)) {
            return StreetClass.SECONDARY;
        } else if ("tertiary".equals(streetClass)) {
            return StreetClass.TERTIARY;
        } else {
            return StreetClass.OTHER;
        }
    }
}
