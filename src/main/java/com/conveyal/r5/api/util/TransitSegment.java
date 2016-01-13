package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
    public List<Route> routes;
    public List<SegmentPattern> segmentPatterns = Lists.newArrayList();
    private transient TransitLayer transitLayer;


    public TransitSegment(TransitLayer transitLayer, Path currentTransitPath, int pathIndex,
        ZonedDateTime fromTimeDateZD, List<TransitJourneyID> transitJourneyIDs) {
        this.transitLayer = transitLayer;
        StreetLayer streetLayer = transitLayer.linkedStreetLayer;
        routes = new ArrayList<>(5);
        int boardStopIdx = currentTransitPath.boardStops[pathIndex];
        int alightStopIdx = currentTransitPath.alightStops[pathIndex];
        TripPattern pattern = currentTransitPath.getPattern(transitLayer, pathIndex);
        if (pattern.routeIndex >= 0) {
            RouteInfo routeInfo = transitLayer.routes.get(pattern.routeIndex);
            //TODO: this needs to be Stop instead of StopCluster in point to point routing
            from = new StopCluster(transitLayer.stopIdForIndex.get(boardStopIdx), transitLayer.stopNames.get(boardStopIdx));
            to = new StopCluster(transitLayer.stopIdForIndex.get(alightStopIdx), transitLayer.stopNames.get(alightStopIdx));
            VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor();
            vertex.seek(transitLayer.streetVertexForStop.get(boardStopIdx));
            from.lat = (float) vertex.getLat();
            from.lon = (float) vertex.getLon();

            vertex.seek(transitLayer.streetVertexForStop.get(alightStopIdx));
            to.lat = (float) vertex.getLat();
            to.lon = (float) vertex.getLon();
            routes.add(Route.from(routeInfo));

            SegmentPattern segmentPattern = new SegmentPattern(transitLayer, pattern, currentTransitPath.patterns[pathIndex], boardStopIdx, alightStopIdx, currentTransitPath.alightTimes[pathIndex], fromTimeDateZD);
            segmentPatterns.add(segmentPattern);
            //FIXME: set pattern and time based on real values
            transitJourneyIDs.add(new TransitJourneyID(0,0));

        }
    }

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

    /**
     * Gets number of seconds between fromDepartureTime and toArrival time
     * for specified pattern and time in that pattern
     *
     * It is assumed that pattern and time exists
     * @param transitJourneyID index of patern and time in selected pattern
     * @return number of seconds between times
     */
    public int getTransitTime(TransitJourneyID transitJourneyID) {
        SegmentPattern segmentPattern = segmentPatterns.get(transitJourneyID.pattern);
        return (int) Duration.between(segmentPattern.fromDepartureTime.get(transitJourneyID.time),
            segmentPattern.toArrivalTime.get(transitJourneyID.time)).getSeconds();
    }
}
