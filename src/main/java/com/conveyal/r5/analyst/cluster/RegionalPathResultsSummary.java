package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import org.apache.commons.lang3.ArrayUtils;

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
     * @param stat         whether the reported per-leg wait times should correspond to the iteration with the minimum or mean
     *                     total waiting time.
     * @return For each destination, a list of String[]s summarizing path templates. For each path template, details
     * of the itinerary with waiting time closest to the requested stat are included.
     */
    public static String[][][] summarize(PathResult[] pathResults, TransitLayer transitLayer, Stat stat) {
        if (pathResults == null) return null;
        int nDestinations = pathResults.length;
        String[][][] summary = new String[nDestinations][][];
        for (int d = 0; d < nDestinations; d++) {
            var pathResult = pathResults[d];
            if (pathResult != null) {
                summary[d] = summarizePathResult(pathResult, transitLayer, stat);
            } else {
                summary[d] = new String[0][];
            }
        }
        return summary;
    }

    public static String[][][] summarize(PathResult[] pathResults, TransitLayer transitLayer) {
        if (pathResults == null) return null;
        return summarize(pathResults, transitLayer, Stat.MINIMUM);
    }

    private static String[][] summarizePathResult(
            PathResult pathResult,
            TransitLayer transitLayer,
            Stat stat
    ) {
        return pathResult.getAllPathTemplates().stream().map(pathTemplate -> summarizePathTemplate(
                pathTemplate,
                pathResult.getIterationsForPathTemplate(pathTemplate),
                transitLayer,
                stat
        )).toArray(String[][]::new);
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
        String[] pathDetails = pathTemplate.detailsWithGtfsIds(transitLayer);

        IntStream totalWaits = iterations.stream().mapToInt(i -> i.waitTimes.sum());
        double targetValue = Stat.MEAN.equals(stat)
                ? totalWaits.average().orElse(-1)
                : totalWaits.min().orElse(-1);
        double score = Double.MAX_VALUE;
        for (PathResult.Iteration iteration : iterations) {
            // TODO clean up, maybe re-using approaches from PathScore?
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
}