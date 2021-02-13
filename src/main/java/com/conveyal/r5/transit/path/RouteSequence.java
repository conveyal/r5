package com.conveyal.r5.transit.path;

import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.StringJoiner;

/** A door-to-door path that includes the routes ridden between stops */
public class RouteSequence {

    /** Route indexes (those used in R5 transit layer) for each transit leg */
    private final TIntList routes;
    public final StopSequence stopSequence;

    /** Convert a pattern-based path into a more general route-based path. */
    public RouteSequence(PatternSequence patternSequence, TransitLayer transitLayer) {
        this.stopSequence = patternSequence.stopSequence;
        this.routes = new TIntArrayList();
        if (patternSequence.patterns != null) {
            patternSequence.patterns.forEach(p -> this.routes.add(transitLayer.tripPatterns.get(p).routeIndex));
        }
    }

    /** Returns details summarizing this route sequence, using GTFS ids stored in the supplied transitLayer. */
    public String[] detailsWithGtfsIds(TransitLayer transitLayer){
        StringJoiner routeIds = new StringJoiner("|");
        StringJoiner boardStopIds = new StringJoiner("|");
        StringJoiner alightStopIds = new StringJoiner("|");
        StringJoiner rideTimes = new StringJoiner("|");
        for (int i = 0; i < routes.size(); i++) {
            routeIds.add(transitLayer.routeString(routes.get(i), false));
            boardStopIds.add(transitLayer.stopString(stopSequence.boardStops.get(i), false));
            alightStopIds.add(transitLayer.stopString(stopSequence.alightStops.get(i), false));
            rideTimes.add(String.format("%.1f", stopSequence.rideTimesSeconds.get(i) / 60f));
        }
        String accessTime = stopSequence.access == null ? null : String.format("%.1f", stopSequence.access.time / 60f);
        String egressTime = stopSequence.egress == null ? null : String.format("%.1f", stopSequence.egress.time / 60f);
        return new String[]{
                routeIds.toString(),
                boardStopIds.toString(),
                alightStopIds.toString(),
                rideTimes.toString(),
                accessTime,
                egressTime
        };
    }

    /** Verbose description of the transit legs in a path, including GTFS ids and names. */
    public Collection<TransitLeg> transitLegs(TransitLayer transitLayer) {
        Collection<TransitLeg> transitLegs = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            String routeString = transitLayer.routeString(routes.get(i), true);
            String boardStop = transitLayer.stopString(stopSequence.boardStops.get(i), true);
            String alightStop = transitLayer.stopString(stopSequence.alightStops.get(i), true);
            transitLegs.add(new TransitLeg(routeString, stopSequence.rideTimesSeconds.get(i), boardStop, alightStop));
        }
        return transitLegs;
    }

    /**
     * String representations of the boarding stop, alighting stop, and route, with in-vehicle time (in
     * minutes).
     */
    public static class TransitLeg {
        public String route;
        public double inVehicleTime;
        public String board;
        public String alight;

        public TransitLeg (String route, int inVehicleTime, String boardStop, String alightStop) {
            this.route = route;
            this.inVehicleTime = Math.round(inVehicleTime / 60f * 10) / 10.0;
            this.board = boardStop;
            this.alight = alightStop;
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteSequence that = (RouteSequence) o;
        return routes.equals(that.routes) &&
                stopSequence.equals(that.stopSequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routes, stopSequence);
    }
}
