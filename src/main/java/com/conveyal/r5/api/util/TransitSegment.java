package com.conveyal.r5.api.util;

import com.beust.jcommander.internal.Lists;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.PathWithTimes;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.vividsolutions.jts.geom.LineString;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;

import static java.util.Collections.singletonList;

/**
 * The equivalent of a ride in an API response. Information degenerates to
 * Strings and ints here.
 */
public class TransitSegment {
    public Stop from;
    public Stop to;

    public Stats waitStats;
    public TransitModes mode;
    public String fromName;
    public String toName;
    public Stats rideStats;
    public Map<Integer, Route> routes;
    public List<TransitEdgeInfo> transitEdges = Lists.newArrayList();
    public List<SegmentPattern> segmentPatterns = Lists.newArrayList();
    // Part of a journey between transit stops (transfers)
    public StreetSegment middle;
    private transient TransitLayer transitLayer;

    public TransitSegment(TransitLayer transitLayer, PathWithTimes currentTransitPath, int pathIndex,
            ZonedDateTime fromTimeDateZD, List<TransitJourneyID> transitJourneyIDs) {
        this(transitLayer, currentTransitPath, pathIndex, fromTimeDateZD, transitJourneyIDs, false);
    }

    /**
     * A constructor version that includes a verbose boolean to control inclusion of
     * transit edges.
     */
    public TransitSegment(TransitLayer transitLayer, PathWithTimes currentTransitPath, int pathIndex,
            ZonedDateTime fromTimeDateZD, List<TransitJourneyID> transitJourneyIDs, boolean verbose) {
        this.transitLayer = transitLayer;
        routes = new HashMap<>();
        int boardStopIdx = currentTransitPath.boardStops[pathIndex];
        int alightStopIdx = currentTransitPath.alightStops[pathIndex];
        TripPattern pattern = currentTransitPath.getPattern(transitLayer, pathIndex);
        if (pattern.routeIndex >= 0) {
            RouteInfo routeInfo = transitLayer.routes.get(pattern.routeIndex);
            from = new Stop(boardStopIdx, transitLayer);
            to = new Stop(alightStopIdx, transitLayer);

            routes.putIfAbsent(pattern.routeIndex, Route.from(routeInfo, pattern.routeIndex));

            SegmentPattern segmentPattern = new SegmentPattern(transitLayer, pattern, currentTransitPath, pathIndex,
                    fromTimeDateZD);
            segmentPatterns.add(segmentPattern);

            if (verbose) {
                transitEdges = TransitSegment.createTransitEdges(pattern, transitLayer, fromTimeDateZD,
                        currentTransitPath, pathIndex);
            }
            // FIXME: set pattern and time based on real values
            transitJourneyIDs.add(new TransitJourneyID(0, 0));
            mode = TransitLayer.getTransitModes(routeInfo.route_type);
            waitStats = currentTransitPath.waitStats[pathIndex];
            rideStats = currentTransitPath.rideStats[pathIndex];
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
            ",\n\t transitEdges=" + transitEdges +
            '}' + "\n\t";
    }

    /**
     * Follows the physical path of a transit pattern to extract the path geometries
     * between stops. Each transit edge is one path between adjacent (theoretically
     * nonexpress) stops within the same transit route.
     */
    public static List<TransitEdgeInfo> createTransitEdges(TripPattern pattern, TransitLayer transitLayer,
            ZonedDateTime fromTimeDateZD, PathWithTimes currentTransitPath, int pathIndex) {
        final List<TransitEdgeInfo> edges = new ArrayList<>();
        final TripSchedule tripSchedule = pattern.tripSchedules.get(currentTransitPath.trips[pathIndex]);
        final int fromStopPatternIdx = currentTransitPath.boardStopPositions[pathIndex];
        final int toStopPatternIdx = currentTransitPath.alightStopPositions[pathIndex];
        final List<LineString> hopGeometries = pattern.getHopGeometries(transitLayer);
        final RouteInfo route = transitLayer.routes.get(pattern.routeIndex);
        for (int i = fromStopPatternIdx; i < toStopPatternIdx; i++) {
            edges.add(new TransitEdgeInfo(pattern.stops[i],
                    singletonList(SegmentPattern.createTime(tripSchedule.departures[i], fromTimeDateZD)),
                    singletonList(SegmentPattern.createTime(tripSchedule.arrivals[i + 1], fromTimeDateZD)),
                    pattern.stops[i + 1], pattern.routeId, route.color, hopGeometries.get(i)));
        }
        return edges;
    }

    /**
     * Gets number of seconds between fromDepartureTime and toArrival time for
     * specified pattern and time in that pattern
     *
     * It is assumed that pattern and time exists
     *
     * @param transitJourneyID index of pattern and time in selected pattern
     * @return number of seconds between times
     */
    public int getTransitTime(TransitJourneyID transitJourneyID) {
        SegmentPattern segmentPattern = segmentPatterns.get(transitJourneyID.pattern);
        return (int) Duration.between(segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                segmentPattern.toArrivalTime.get(transitJourneyID.time)).getSeconds();
    }

    /**
     * Gets approximate distance in m for this transit segment, as the crow flies.
     */
    public double getTransitDistanceM() {
        return GeometryUtils.distance(from.lat, from.lon, to.lat, to.lon);
    }

    /**
     * Add new segmentPattern if needed otherwise just adds new time to existing one
     * <p>
     * If there is already segment pattern with same patternIndex as pattern in
     * current Path it only adds times to this pattern if needed. It also updates
     * routes array if needed and transitJourneyID
     *
     * @param transitLayer
     * @param currentTransitPath object with transit information (board/alight stop,
     *                           pattern and alight time)
     * @param pathIndex          index of current leg of transit journey inside
     *                           currentTransitPath
     * @param fromTimeDateZD     requested fromTime from which date and timezone is
     *                           read
     * @param transitJourneyIDs  this is updated with created/found indexes of
     *                           pattern and time
     */
    public void addSegmentPattern(TransitLayer transitLayer, Path currentTransitPath, int pathIndex,
            ZonedDateTime fromTimeDateZD, List<TransitJourneyID> transitJourneyIDs) {
        // If we are here we can be sure that from stop and to stop are the same in all
        // segment patterns as in currentTransitPath
        // We only need to check pattern
        int currentPatternID = currentTransitPath.patterns[pathIndex];
        int segmentPatternIdx = 0;
        for (SegmentPattern segmentPattern : segmentPatterns) {
            if (currentPatternID == segmentPattern.patternIdx) {
                int timeIndex = segmentPattern.addTime(transitLayer, currentPatternID,
                        currentTransitPath.alightTimes[pathIndex], fromTimeDateZD, currentTransitPath.trips[pathIndex]);
                transitJourneyIDs.add(new TransitJourneyID(segmentPatternIdx, timeIndex));
                return;
            }
            segmentPatternIdx++;
        }

        // This pattern doesn't exist yet in this transitSegment we need to create it
        final TripPattern tripPattern = currentTransitPath.getPattern(transitLayer, pathIndex);
        SegmentPattern segmentPattern = new SegmentPattern(transitLayer, tripPattern, currentTransitPath, pathIndex,
                fromTimeDateZD);
        segmentPatterns.add(segmentPattern);
        // Time is always 0 here since we created new segmentPattern
        transitJourneyIDs.add(new TransitJourneyID(segmentPatterns.size() - 1, 0));
        // Adds route to routeMap if needed
        if (tripPattern.routeIndex >= 0) {
            RouteInfo routeInfo = transitLayer.routes.get(tripPattern.routeIndex);
            routes.putIfAbsent(tripPattern.routeIndex, Route.from(routeInfo, tripPattern.routeIndex));
        }
    }

    /**
     * @param currentTransitPath transit part with transfers
     * @param pathIndex          index of current leg in currentTransitPath
     * @return true if transit leg at pathIndex has same board and alight stop as
     *         current transitSegment
     */
    public boolean hasSameStops(Path currentTransitPath, int pathIndex) {
        int boardStopIdx = currentTransitPath.boardStops[pathIndex];
        int alightStopIdx = currentTransitPath.alightStops[pathIndex];
        String fromStopID = transitLayer.stopIdForIndex.get(boardStopIdx);
        String toStopID = transitLayer.stopIdForIndex.get(alightStopIdx);

        return from.stopId.equals(fromStopID) && to.stopId.equals(toStopID);
    }

    public Collection<Route> getRoutes() {
        return routes.values();
    }

    public void addMiddle(StreetSegment streetSegment) {
        middle = streetSegment;
    }
}
