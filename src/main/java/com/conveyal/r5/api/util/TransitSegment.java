package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The equivalent of a ride in an API response. Information degenerates to Strings and ints here.
 */
public class TransitSegment {




    //TODO: this is stopCluster in Profile and Stop in point to point
    //from, to and fromName, toName are actually from stopCluster
    // Use AgencyAndId instead of String to get both since we are now multi-feed
    @JsonProperty("from")
    private String fromId;
    @JsonProperty("to")
    private String toId;

    public StopCluster from;
    public StopCluster to;

    //this is index of non transit part of a journey in middle between this transit part and the next
    public  int middle_id;
    public Stats waitStats;
    public TransitModes mode;
    public String fromName;
    public String toName;
    public Stats rideStats;
    public List<RouteShort> routes;
    public List<SegmentPattern> segmentPatterns = Lists.newArrayList();

    @Override public String toString() {
        return "TransitSegment{" +
            "from='" + from + '\'' +
            ", to='" + to + '\'' +
            ", waitStats=" + waitStats +
            ", mode='" + mode + '\'' +
            ", fromName='" + fromName + '\'' +
            ", toName='" + toName + '\'' +
            ", rideStats=" + rideStats +
            ",\n\t routes=" + routes +
            ",\n\t segmentPatterns=" + segmentPatterns +
            '}' +"\n\t";
    }

    public String getFromId() {
        return fromId;
    }

    public String getToId() {
        return toId;
    }
}
