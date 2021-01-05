package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.path.PathTemplate;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Holds paths and associated details from an origin to destination target(s) at every Raptor iteration. For
 * single-point tasks, paths to only a single destination (specified by toLon and toLat coordinates in the task) are
 * recorded.
 *
 * This class is used to accumulate paths to be returned to the broker (similar to TravelTimeResult). In contrast,
 * workers use PathWriter to write paths directly to S3 for Taui sites.
 */

public class PathResult {

    private final int nDestinations;
    private final int nIterations;
    /**
     * Array with one entry per destination. Each entry is a map from each "path template" (i.e. a path ignoring
     * per-iteration details such as wait time) to the associated iteration details.
     */
    // TODO clean this up: maybe use a BasicPath (boardStopIds, alightStopIds, route or pattern Ids), extended by
    //  a Path (adds tripIds and associated boarding/alighting times), extended by a FullItinerary (includes specific
    //  iteration number/departure time, wait time details, etc.). Or just track full paths with the per-iteration
    //  details, if this deduplication isn't worth it.
    private final Multimap<PathTemplate, Iteration>[] iterationsForPathTemplates;
    private final TransitLayer transitLayer;

    public static String[] DATA_COLUMNS = new String[]{
            "routes",
            "boardStops",
            "alightStops",
            "rideTimes",
            "accessTime",
            "egressTime",
            "transferTime",
            "nIterations",
            "waitTime"
    };

    public PathResult(AnalysisWorkerTask task, TransitLayer transitLayer) {
        if (task instanceof TravelTimeSurfaceTask) {
            // In interactive single-point tasks, paths are only returned for one destination
            nDestinations = 1;
        } else {
            // In regional analyses, return paths to all destinations
            nDestinations = task.nTargetsPerOrigin();
            // This limitation reflects the initial design, for use with freeform pointset destinations
            if (nDestinations > 5000) throw new UnsupportedOperationException("Path results are limited to 5000 " +
                    "destinations");
        }
        this.nIterations = task.getTotalIterations(transitLayer.hasFrequencies);
        iterationsForPathTemplates = new Multimap[nDestinations];
        this.transitLayer = transitLayer;
    }

    public void setTarget(int targetIndex, Multimap<PathTemplate, Iteration> pathsToTarget) {
        iterationsForPathTemplates[targetIndex] = pathsToTarget;
    }

    public static class Iteration {
        public int departureTime;
        public int[] waitTimes;
        public int totalTime;

        public Iteration(Path path, int totalTime) {
            this.departureTime = path.departureTime;
            this.waitTimes = path.waitTimes;
            this.totalTime = totalTime;
        }
    }

    public static class HumanReadableIteration {
        public String departureTime;
        public double[] waitTimes;
        public double totalTime;

        HumanReadableIteration(Iteration iteration) {
            this.departureTime =
                    String.format("%02d:%02d", Math.floorDiv(iteration.departureTime, 3600),
                            (int) (iteration.departureTime / 60.0 % 60));
            this.waitTimes =  Arrays.stream(iteration.waitTimes).mapToDouble(wait -> wait / 60f).toArray();
            this.totalTime =  iteration.totalTime / 60f;
        };
    }

    /**
     * Summary of iterations for each destination. Conversion to strings happens here (on workers) to minimize work
     * that the assembler needs to perform.
     *
     * @param stat whether the reported per-leg wait times should correspond to the trip with the minimum or mean
     *             total travel time.
     * @return For each destination, a list of String[]s that summarize the itineraries (at specific departure times)
     * for various path templates are optimal.
     */
    public ArrayList<String[]>[] summarizeIterations(Stat stat) {
        ArrayList<String[]>[] summary = new ArrayList[nDestinations];
        for (int d = 0; d < nDestinations; d++) {
            summary[d] = new ArrayList<>();
            Multimap<PathTemplate, Iteration> itinerariesForPathTemplate = iterationsForPathTemplates[d];
            if (itinerariesForPathTemplate != null) {
                for (PathTemplate pathTemplate : itinerariesForPathTemplate.keySet()) {
                    String[] path = pathTemplate.detailsWithGtfsIds(transitLayer);
                    int nIterations = itinerariesForPathTemplate.get(pathTemplate).size();
                    Iteration[] itineraries =
                            itinerariesForPathTemplate.get(pathTemplate).toArray(new Iteration[nIterations]);
                    String waitTimes = null;
                    String totalTime = null;
                    double targetValue;
                    IntStream totalWaits = Arrays.stream(itineraries).mapToInt(i -> Arrays.stream(i.waitTimes).sum());
                    if (stat == Stat.MINIMUM) {
                        targetValue = totalWaits.min().orElse(-1);
                    } else if (stat == Stat.MEAN){
                        targetValue = totalWaits.average().orElse(-1);
                    } else {
                        throw new RuntimeException("Unrecognized statistic for path summary");
                    }
                    double score = Double.MAX_VALUE;
                    for (int i = 0; i < nIterations; i++) {
                        StringJoiner waits = new StringJoiner("|");
                        // TODO clean up, maybe re-using approaches from PathScore? There is a way to bail-out early
                        //  when looking for the minimum, but added branches create additional maintenance overhead.
                        double thisScore = Math.abs(targetValue - Arrays.stream(itineraries[i].waitTimes).sum());
                        if (thisScore < score) {
                            Arrays.stream(itineraries[i].waitTimes).forEach(
                                    wait -> waits.add(String.format("%.1f", wait / 60f))
                            );
                            score = thisScore;
                            waitTimes = waits.toString();
                            totalTime = String.format("%.1f", itineraries[i].totalTime / 60f);
                        }
                    }
                    String[] row = ArrayUtils.addAll(path, waitTimes, totalTime, String.valueOf(nIterations));
                    Preconditions.checkState(row.length == DATA_COLUMNS.length);
                    summary[d].add(row);
                }
            }
        }
        return summary;
    }

    public enum Stat {
        MEAN,
        MINIMUM
    }

    public static class PathIterations {
        public PathTemplate.Summary pathSummary;
        public Collection<Iteration> iterations;

        PathIterations(PathTemplate.Summary pathSummary, Collection<Iteration> iterations) {
            this.pathSummary = pathSummary;
            this.iterations = iterations;
        }
    }

    /**
     * Returns a summary of path iterations suitable for JSON representation.
     */
    List<PathIterations> getSummaryForDestination() {
        Preconditions.checkState(iterationsForPathTemplates.length == 1, "Paths were stored for multiple " +
                "destinations, but only one is being requested");
        List<PathIterations> summaryToDestination = new ArrayList<>();
        Multimap<PathTemplate, Iteration> iterations = iterationsForPathTemplates[0];
        if (iterations != null) {
            for (PathTemplate path : iterationsForPathTemplates[0].keySet()) {
                PathTemplate.Summary pathSummary = path.summary(transitLayer);
                summaryToDestination.add(new PathIterations(pathSummary,
                        iterations.get(path).stream().sorted(Comparator.comparingInt(p -> p.departureTime)).collect(Collectors.toList())));
            }
        }
        return summaryToDestination;
    }
}