package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;

public class PathTemplate {
    public int[] patterns;
    public int[] boardStops;
    public int[] alightStops;
    public int[] rideTimesSeconds;
    public StreetTimesAndModes.StreetTimeAndMode access;
    public StreetTimesAndModes.StreetTimeAndMode egress;
    public int transferTimeSeconds;

    PathTemplate(int length) {
        this.patterns = new int[length];
        this.boardStops = new int[length];
        this.alightStops = new int[length];
        this.rideTimesSeconds = new int[length];
    }

    public String[] detailsWithGtfsIds(TransitLayer transitLayer){
        StringJoiner routeIds = new StringJoiner("|");
        StringJoiner boardStopIds = new StringJoiner("|");
        StringJoiner alightStopIds = new StringJoiner("|");
        StringJoiner rideTimes = new StringJoiner("|");
        for (int i = 0; i < patterns.length; i++) {
            routeIds.add(transitLayer.routeString(patterns[i], false));
            boardStopIds.add(transitLayer.stopString(boardStops[i], false));
            alightStopIds.add(transitLayer.stopString(alightStops[i], false));
            rideTimes.add(String.format("%.2f", rideTimesSeconds[i]/ 60f));
        }
        return new String[]{
                routeIds.toString(),
                boardStopIds.toString(),
                alightStopIds.toString(),
                rideTimes.toString(),
                String.format("%.2f", access.time / 60f),
                String.format("%.2f", egress.time / 60f),
                String.format("%.2f", transferTimeSeconds / 60f)
        };
    }

    public Summary summary(TransitLayer transitLayer) {
        Collection<TransitLeg> transitLegs = new ArrayList<>();
        for (int i = 0; i < patterns.length; i++) {
            String routeString = transitLayer.routeString(patterns[i], true);
            String boardStop = transitLayer.stopString(boardStops[i], true);
            String alightStop = transitLayer.stopString(alightStops[i], true);
            transitLegs.add(new TransitLeg(routeString, rideTimesSeconds[i], boardStop, alightStop));
        }
        return new Summary(this.access, transitLegs, this.egress);
    }

    public static class Summary {
        public StreetTimesAndModes.StreetTimeAndMode access;
        public Collection<TransitLeg> transitLegs;
        public StreetTimesAndModes.StreetTimeAndMode egress;

        public Summary(StreetTimesAndModes.StreetTimeAndMode access, Collection<TransitLeg> transitLegs, StreetTimesAndModes.StreetTimeAndMode egress) {
            this.access = access;
            this.transitLegs = transitLegs;
            this.egress = egress;
        }

    }

    public static class TransitLeg {
        public String route;
        public double inVehicleTime;
        public String from;
        public String to;

        public TransitLeg (String route, double inVehicleTime, String boardStop, String alightStop) {
            this.route = route;
            this.inVehicleTime = inVehicleTime;
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
        result = 31 * result + Ints.hashCode(transferTimeSeconds);
        return result;
    }

}
