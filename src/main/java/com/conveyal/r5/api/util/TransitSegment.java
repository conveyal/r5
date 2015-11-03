package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The equivalent of a ride in an API response. Information degenerates to Strings and ints here.
 */
public class TransitSegment {




    //from, to and fromName, toName are actually from stopCluster
    // Use AgencyAndId instead of String to get both since we are now multi-feed
    @JsonProperty("from")
    private String fromId;
    @JsonProperty("to")
    private String toId;

    public StopCluster from;
    public StopCluster to;
    //time in seconds @notnull
    public int walkTime;
    //distance of walking in meters @notnull
    public int walkDistance;
    public Stats waitStats;
    public TransitModes mode;
    public String fromName;
    public String toName;
    public Stats rideStats;
    public List<RouteShort> routes;
    public List<SegmentPattern> segmentPatterns = Lists.newArrayList();
    public String startTime;
    public String endTime;

    @Override public String toString() {
        return "TransitSegment{" +
            "from='" + from + '\'' +
            ", to='" + to + '\'' +
            ", walkTime=" + walkTime +
            ", walkDistance=" + walkDistance +
            ", waitStats=" + waitStats +
            ", mode='" + mode + '\'' +
            ", fromName='" + fromName + '\'' +
            ", toName='" + toName + '\'' +
            ", rideStats=" + rideStats +
            ",\n\t routes=" + routes +
            ",\n\t segmentPatterns=" + segmentPatterns +
            ",\n\t startTime='" + startTime + '\'' +
            ", endTime='" + endTime + '\'' +
            '}' +"\n\t";
    }
}
