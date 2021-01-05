package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;

import static com.google.common.base.Preconditions.checkState;

/**
 * A door-to-door path, i.e. access characteristics, transit legs (keyed on characteristics including per-leg
 * in-vehicle times), and egress characteristics, which may be repeated at different departure times.
 *
 * Instances are constructed initially from transit legs, with access, egress, and transferTimes set in successive
 * operations.
 */
public class PathTemplate {
    private final int[] patterns;
    private final int[] boardStops;
    private final int[] alightStops;
    private final int[] rideTimesSeconds;
    public StreetTimesAndModes.StreetTimeAndMode access;
    public StreetTimesAndModes.StreetTimeAndMode egress;
    // This could be calculated from other fields, but we explicitly calculate it for convenience
    private int transferTimeSeconds;

    /**
     * Create a PathTemplate from transit leg characteristics.
     */
    PathTemplate(int[] patterns, int[] boardStops, int[] alightStops, int[] rideTimesSeconds) {
        this.patterns = patterns;
        this.boardStops = boardStops;
        this.alightStops = alightStops;
        this.rideTimesSeconds = rideTimesSeconds;
    }

    /**
     * Create a pathTemplate by copying the transit leg and access fields of a source pathTemplate. The source's
     * egress and transfer properties are ignored.
     */
    public PathTemplate(PathTemplate source) {
        this.patterns = source.patterns;
        this.boardStops = source.boardStops;
        this.alightStops = source.alightStops;
        this.rideTimesSeconds = source.rideTimesSeconds;
        this.access = source.access;
    }


    /**
     * Returns details summarizing a path template, using GTFS ids stored in the supplied transitLayer.
     */
    public String[] detailsWithGtfsIds(TransitLayer transitLayer){
        StringJoiner routeIds = new StringJoiner("|");
        StringJoiner boardStopIds = new StringJoiner("|");
        StringJoiner alightStopIds = new StringJoiner("|");
        StringJoiner rideTimes = new StringJoiner("|");
        for (int i = 0; i < patterns.length; i++) {
            routeIds.add(transitLayer.routeString(patterns[i], false));
            boardStopIds.add(transitLayer.stopString(boardStops[i], false));
            alightStopIds.add(transitLayer.stopString(alightStops[i], false));
            rideTimes.add(String.format("%.1f", rideTimesSeconds[i]/ 60f));
        }
        return new String[]{
                routeIds.toString(),
                boardStopIds.toString(),
                alightStopIds.toString(),
                rideTimes.toString(),
                String.format("%.1f", access.time / 60f),
                String.format("%.1f", egress.time / 60f),
                String.format("%.1f", transferTimeSeconds / 60f)
        };
    }

    /**
     * Verbose description of the transit legs in a path, including GTFS ids and names.
     */
    public Collection<TransitLeg> transitLegs(TransitLayer transitLayer) {
        Collection<TransitLeg> transitLegs = new ArrayList<>();
        for (int i = 0; i < patterns.length; i++) {
            String routeString = transitLayer.routeString(patterns[i], true);
            String boardStop = transitLayer.stopString(boardStops[i], true);
            String alightStop = transitLayer.stopString(alightStops[i], true);
            transitLegs.add(new TransitLeg(routeString, rideTimesSeconds[i], boardStop, alightStop));
        }
        return transitLegs;
    }

    /**
     * String representations of the boarding (from) stop, alighting (to) stop, and route, with in-vehicle time (in
     * minutes).
     */
    public static class TransitLeg {
        public String route;
        public double inVehicleTime;
        public String from;
        public String to;

        public TransitLeg (String route, int inVehicleTime, String boardStop, String alightStop) {
            this.route = route;
            this.inVehicleTime = Math.round(inVehicleTime / 60f * 10) / 10.0;
            this.from = boardStop;
            this.to = alightStop;
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathTemplate path = (PathTemplate) o;
        return Arrays.equals(patterns, path.patterns) &&
                Arrays.equals(boardStops, path.boardStops) &&
                Arrays.equals(alightStops, path.alightStops) &&
                Arrays.equals(rideTimesSeconds, path.rideTimesSeconds) &&
                this.access.equals(path.access) &&
                this.egress.equals(path.egress) &&
                this.transferTimeSeconds == path.transferTimeSeconds;
    }

    @Override
    // We tried replacing this custom implementation with Objects.hash(...), but it did not produce the expected
    // results. TODO investigate and replace with stock hash function
    public int hashCode() {
        int result = Arrays.hashCode(patterns);
        result = 31 * result + Arrays.hashCode(boardStops);
        result = 31 * result + Arrays.hashCode(alightStops);
        result = 31 * result + Arrays.hashCode(rideTimesSeconds);
        result = 31 * result + access.hashCode();
        result = 31 * result + egress.hashCode();
        result = 31 * result + Ints.hashCode(transferTimeSeconds);
        return result;
    }

    public void setAccess(StreetTimesAndModes bestAccessOptions) {
        access = bestAccessOptions.streetTimesAndModes.get(boardStops[0]);
    }

    public void setEgress(StreetTimesAndModes.StreetTimeAndMode egress) {
        this.egress = egress;
    }

    public void setTransferTime(int totalTime, int[] waitTimes) {
        checkState(access != null);
        checkState(egress != null);
        transferTimeSeconds = totalTime - access.time - egress.time
                - Arrays.stream(waitTimes).sum() - Arrays.stream(rideTimesSeconds).sum();
        checkState(transferTimeSeconds >= 0);
    }


}
