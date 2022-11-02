package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.StringJoiner;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkState;

public class RegionalPathResultsSummary {
    public static String[] DATA_COLUMNS = new String[]{
            "routes",
            "boardStops",
            "alightStops",
            "rideTimes",
            "accessTime",
            "egressTime",
            "transferTime",
            "waitTimes",
            "totalTime",
            "nIterations"
    };

    public static String DELIMITER = "|";

    enum Stat {
        MEAN,
        MINIMUM
    }

    /**
     * Summary of iterations for each destination, suitable for writing to a CSV. Conversion to strings happens here
     * (on distributed workers) to minimize pressure on the central Broker's assembler.
     *
     * @param pathResults  Path results to all destinations to generate summary from.
     * @param transitLayer Transit layer to retrieve data from.
     * @param stat         whether the reported per-leg wait times should correspond to the iteration with the minimum
     *                     or mean total waiting time.
     * @return For each destination, a list of String[]s summarizing path templates. For each path template, details
     * of the itinerary with waiting time closest to the requested stat are included.
     */
    public static String[][][] summarize(PathResult[] pathResults, TransitLayer transitLayer, Stat stat) {
        if (pathResults == null) return null;
        var summary = new ArrayList<String[][]>();
        for (PathResult pathResult : pathResults) {
            if (pathResult != null) {
                summary.add(summarizePathResult(pathResult, transitLayer, stat));
            } else {
                summary.add(new String[][]{});
            }
        }
        return summary.toArray(new String[0][][]);
    }

    /**
     * Default usage of summarize is with the minimum
     */
    public static String[][][] summarize(PathResult[] pathResults, TransitLayer transitLayer) {
        if (pathResults == null) return null;
        return summarize(pathResults, transitLayer, Stat.MINIMUM);
    }

    private static String[][] summarizePathResult(
            PathResult pathResult,
            TransitLayer transitLayer,
            Stat stat
    ) {
        var pathTemplates = pathResult.getAllPathTemplates();
        var summarizedPathResults = new ArrayList<String[]>();
        for (var pathTemplate : pathTemplates) {
            summarizedPathResults.add(summarizePathTemplate(
                    pathTemplate,
                    pathResult.getIterationsForPathTemplate(pathTemplate),
                    transitLayer,
                    stat
            ));
        }
        return summarizedPathResults.toArray(String[][]::new);
    }

    /**
     * Summarize a path template and its iterations into a single CSV row.
     */
    private static String[] summarizePathTemplate(
            PathResult.PathTemplate pathTemplate,
            Collection<PathResult.Iteration> iterations,
            TransitLayer transitLayer,
            Stat stat
    ) {
        int nIterations = iterations.size();
        checkState(nIterations > 0, "A path was stored without any iterations");
        String transfer = null, totalTime = null, waits = null;
        String[] pathDetails = detailsWithGtfsIds(pathTemplate, transitLayer);

        IntStream totalWaits = iterations.stream().mapToInt(i -> i.waitTimes.sum());
        double targetValue = Stat.MEAN.equals(stat)
                ? totalWaits.average().orElse(-1)
                : totalWaits.min().orElse(-1);
        double score = Double.MAX_VALUE;
        for (PathResult.Iteration iteration : iterations) {
            int totalWaitTime = iteration.waitTimes.sum();
            double thisScore = Math.abs(targetValue - iteration.waitTimes.sum());
            if (thisScore < score) {
                StringJoiner waitTimes = new StringJoiner("|");
                iteration.waitTimes.forEach(w -> {
                    waitTimes.add(String.format("%.1f", w / 60f));
                    return true;
                });
                waits = waitTimes.toString();
                int transferTime = getTransferTime(
                        iteration.totalTime,
                        pathTemplate.access,
                        pathTemplate.egress,
                        pathTemplate.stopSequence.rideTimesSeconds,
                        totalWaitTime
                );
                transfer = String.format("%.1f", transferTime / 60f);
                totalTime = String.format("%.1f", iteration.totalTime / 60f);

                if (thisScore == 0) break;
                score = thisScore;
            }
        }
        String[] row = ArrayUtils.addAll(pathDetails, transfer, waits, totalTime, String.valueOf(nIterations));
        checkState(row.length == DATA_COLUMNS.length);
        return row;
    }

    /**
     * Get the time spent transferring between stops, which is not stored in our Raptor implementation but can be
     * calculated by subtracting the other components of travel time from the total travel time
     */
    private static int getTransferTime(int totalTime, StreetTimeAndMode access, StreetTimeAndMode egress, TIntList rideTimesSeconds, int totalWaitTime) {
        if (access == null && egress == null && totalWaitTime == 0 && rideTimesSeconds == null) {
            // No transit ridden, so transfer time is 0.
            return 0;
        } else {
            int transferTimeSeconds = totalTime - access.time - egress.time - totalWaitTime - rideTimesSeconds.sum();
            checkState(transferTimeSeconds >= 0);
            return transferTimeSeconds;
        }
    }

    /**
     * Returns details summarizing this path template, using GTFS ids stored in the supplied transitLayer.
     */
    public static String[] detailsWithGtfsIds(
            PathResult.PathTemplate path,
            TransitLayer transitLayer
    ) {
        var routeIds = new StringJoiner(DELIMITER);
        var boardStopIds = new StringJoiner(DELIMITER);
        var alightStopIds = new StringJoiner(DELIMITER);
        var rideTimes = new StringJoiner(DELIMITER);
        for (int i = 0; i < path.routeIndexes.size(); i++) {
            routeIds.add(transitLayer.routes.get(path.routeIndexes.get(i)).route_id);
            boardStopIds.add(transitLayer.getStopId(path.stopSequence.boardStops.get(i)));
            alightStopIds.add(transitLayer.getStopId(path.stopSequence.alightStops.get(i)));
            rideTimes.add(String.format("%.1f", path.stopSequence.rideTimesSeconds.get(i) / 60f));
        }
        var accessTime = path.access == null ? null : String.format("%.1f", path.access.time / 60f);
        var egressTime = path.egress == null ? null : String.format("%.1f", path.egress.time / 60f);
        return new String[]{
                routeIds.toString(),
                boardStopIds.toString(),
                alightStopIds.toString(),
                rideTimes.toString(),
                accessTime,
                egressTime
        };
    }
}