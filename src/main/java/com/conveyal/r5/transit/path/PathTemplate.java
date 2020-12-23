package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.primitives.Ints;

import java.util.Arrays;
import java.util.Objects;
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
            // TODO use a compact feed index, instead of splitting to remove feedIds
            routeIds.add(transitLayer.tripPatterns.get(patterns[i]).routeId.split(":")[1]);
            boardStopIds.add(transitLayer.stopIdForIndex.get(boardStops[i]).split(":")[1]);
            alightStopIds.add(transitLayer.stopIdForIndex.get(alightStops[i]).split(":")[1]);
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

    public String[] toTripString(TransitLayer transitLayer) {
        String[] pathSummary = new String[patterns.length + 2];
        pathSummary[0] = access.toString();
        for (int i = 0; i < patterns.length; i++) {
            var builder = new StringBuilder();
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(patterns[i]).routeIndex);
            builder.append(route.route_id)
                    // .append(" (").append(route.route_short_name).append(")")
                    .append(" | ")
                    .append(String.format("%.1f", rideTimesSeconds[i] / 60.0)).append (" min. | ")
                    .append(transitLayer.stopIdForIndex.get(boardStops[i]).split(":")[1])
                    .append(" (").append(transitLayer.stopNames.get(boardStops[i])).append(") -> ")
                    .append(transitLayer.stopIdForIndex.get(alightStops[i]).split(":")[1])
                    .append(" (").append(transitLayer.stopNames.get(alightStops[i])).append(")");
            pathSummary[i + 1] = builder.toString();
        }
        pathSummary[pathSummary.length - 1] = egress.toString();
        return pathSummary;
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
