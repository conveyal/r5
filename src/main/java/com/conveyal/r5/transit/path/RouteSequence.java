package com.conveyal.r5.transit.path;

import com.conveyal.analysis.models.CsvResultOptions;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransitLayer.EntityRepresentation;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.StringJoiner;

import static com.conveyal.r5.transit.TransitLayer.EntityRepresentation.NAME_AND_ID;

/** A door-to-door path that includes the routes ridden between stops */
public class RouteSequence {

    /**
     * Route indexes (those used in R5 transit layer) for each transit leg
     */
    public final TIntList routes;
    public final StopSequence stopSequence;

    /** Convert a pattern-based path into a more general route-based path. */
    public RouteSequence(PatternSequence patternSequence, TransitLayer transitLayer) {
        this.stopSequence = patternSequence.stopSequence;
        this.routes = new TIntArrayList();
        if (patternSequence.patterns != null) {
            patternSequence.patterns.forEach(p -> this.routes.add(transitLayer.tripPatterns.get(p).routeIndex));
        }
    }

    /**
     * Returns details summarizing this route sequence, using GTFS ids stored in the supplied transitLayer.
     * @param csvOptions indicates whether names or IDs should be returned for certain fields.
     * @return array of pipe-concatenated strings, with the route, board stop, alight stop, ride time, and feed for
     * each transit leg, as well as the access and egress time.
     * 
     * If csvOptions.feedRepresentation is not null, the feed values will be R5-generated UUID for boarding stop of
     * each leg. We are grabbing the feed ID from the stop rather than the route (which might seem like a better
     * representative of the leg) because stops happen to have a readily available feed ID.
     */
    public String[] detailsWithGtfsIds (TransitLayer transitLayer, CsvResultOptions csvOptions){
        StringJoiner routeJoiner = new StringJoiner("|");
        StringJoiner boardStopJoiner = new StringJoiner("|");
        StringJoiner alightStopJoiner = new StringJoiner("|");
        StringJoiner feedJoiner = new StringJoiner("|");
        StringJoiner rideTimeJoiner = new StringJoiner("|");
        for (int i = 0; i < routes.size(); i++) {
            routeJoiner.add(transitLayer.routeString(routes.get(i), csvOptions.routeRepresentation));
            boardStopJoiner.add(transitLayer.stopString(stopSequence.boardStops.get(i), csvOptions.stopRepresentation));
            alightStopJoiner.add(transitLayer.stopString(stopSequence.alightStops.get(i), csvOptions.stopRepresentation));
            if (csvOptions.feedRepresentation != null) {
                feedJoiner.add(transitLayer.feedFromStop(stopSequence.boardStops.get(i)));
            }
            rideTimeJoiner.add(String.format("%.1f", stopSequence.rideTimesSeconds.get(i) / 60f));
        }
        String accessTime = stopSequence.access == null ? null : String.format("%.1f", stopSequence.access.time / 60f);
        String egressTime = stopSequence.egress == null ? null : String.format("%.1f", stopSequence.egress.time / 60f);
        return new String[]{
                routeJoiner.toString(),
                boardStopJoiner.toString(),
                alightStopJoiner.toString(),
                feedJoiner.toString(),    
                rideTimeJoiner.toString(),
                accessTime,
                egressTime
        };
    }

    /** Verbose description of the transit legs in a path, including GTFS ids and names. */
    public Collection<TransitLeg> transitLegs(TransitLayer transitLayer) {
        Collection<TransitLeg> transitLegs = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            String routeString = transitLayer.routeString(routes.get(i), NAME_AND_ID);
            String boardStop = transitLayer.stopString(stopSequence.boardStops.get(i), NAME_AND_ID);
            String alightStop = transitLayer.stopString(stopSequence.alightStops.get(i), NAME_AND_ID);
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
