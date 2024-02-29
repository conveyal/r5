package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.models.CsvResultOptions;
import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.conveyal.r5.transit.path.PatternSequence;
import com.conveyal.r5.transit.path.RouteSequence;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkState;

/**
 * Holds paths and associated details from an origin to destination target(s) at every Raptor iteration. For
 * single-point tasks, paths to only a single destination (specified by toLon and toLat coordinates in the task) are
 * recorded.
 *
 * This class is used to accumulate paths to be returned to the broker (similar to TravelTimeResult). In contrast,
 * workers use PathWriter to write paths directly to S3 for Taui sites.
 */

public class PathResult {

    /**
     * The maximum number of destinations for which we'll generate detailed path information in a single request.
     * Detailed path information was added on to the original design, which returned a simple grid of travel times.
     * These results are returned to the backend over an HTTP API so we don't want to risk making them too huge.
     * This could be set to a higher number in cases where you know the result return channel can handle the size.
     */
    public static final int MAX_PATH_DESTINATIONS = 5_000;

    private final int nDestinations;
    /**
     * Array with one entry per destination. Each entry is a map from a "path template" to the associated iteration
     * details. For now, the path template is a route-based path ignoring per-iteration details such as wait time.
     * With additional changes, patterns could be collapsed further to route combinations or modes.
     */
    public final Multimap<RouteSequence, Iteration>[] iterationsForPathTemplates;

    private final TransitLayer transitLayer;

    private final CsvResultOptions csvOptions;

    public static final String[] DATA_COLUMNS = new String[]{
            "routes",
            "boardStops",
            "alightStops",
            "feedIds",
            "rideTimes",
            "accessTime",
            "egressTime",
            "transferTime",
            "waitTimes",
            "totalTime",
            "nIterations",
            "group"
    };

    public PathResult(AnalysisWorkerTask task, TransitLayer transitLayer) {
        if (task instanceof TravelTimeSurfaceTask) {
            // In interactive single-point tasks, paths are only returned for one destination
            nDestinations = 1;
        } else {
            // In regional analyses, return paths to all destinations
            nDestinations = task.nTargetsPerOrigin();
            // This limitation reflects the initial design, for use with freeform pointset destinations
            if (nDestinations > MAX_PATH_DESTINATIONS) {
                throw new UnsupportedOperationException("Number of detailed path destinations exceeds limit of " + MAX_PATH_DESTINATIONS);
            }
        }
        iterationsForPathTemplates = new Multimap[nDestinations];
        this.transitLayer = transitLayer;
        this.csvOptions = task.csvResultOptions;
    }

    /**
     * Populate the multimap of path templates to iterations, reducing by using route-based keys instead of
     * pattern-based keys
     */
    public void setTarget(int targetIndex, Multimap<PatternSequence, Iteration> patterns) {
        Multimap<RouteSequence, Iteration> routes = HashMultimap.create();
        patterns.forEach(((patternSeq, iteration) -> routes.put(new RouteSequence(patternSeq, transitLayer), iteration)));
        iterationsForPathTemplates[targetIndex] = routes;
    }

    /**
     * Summary of iterations for each destination, suitable for writing to a CSV. Conversion to strings happens here
     * (on distributed workers) to minimize pressure on the central Broker's assembler.
     *
     * @param stat whether the reported per-leg wait times should correspond to the iteration with the minimum or mean
     *             total waiting time.
     * @return For each destination, a list of String[]s summarizing path templates. For each path template, details
     *          of the itinerary with waiting time closest to the requested stat are included.
     */
    public ArrayList<String[]>[] summarizeIterations(Stat stat) {
        ArrayList<String[]>[] summary = new ArrayList[nDestinations];
        for (int d = 0; d < nDestinations; d++) {
            summary[d] = new ArrayList<>();
            Multimap<RouteSequence, Iteration> iterationMap = iterationsForPathTemplates[d];
            if (iterationMap != null) {
                for (RouteSequence routeSequence: iterationMap.keySet()) {
                    Collection<Iteration> iterations = iterationMap.get(routeSequence);
                    int nIterations = iterations.size();
                    checkState(nIterations > 0, "A path was stored without any iterations");
                    String waits = null, transfer = null, totalTime = null;
                    String[] path = routeSequence.detailsWithGtfsIds(transitLayer, csvOptions);
                    double targetValue;
                    IntStream totalWaits = iterations.stream().mapToInt(i -> i.waitTimes.sum());
                    if (stat == Stat.MINIMUM) {
                        targetValue = totalWaits.min().orElse(-1);
                    } else if (stat == Stat.MEAN){
                        targetValue = totalWaits.average().orElse(-1);
                    } else {
                        throw new RuntimeException("Unrecognized statistic for path summary");
                    }
                    double score = Double.MAX_VALUE;
                    for (Iteration iteration: iterations) {
                        // TODO clean up, maybe re-using approaches from PathScore?
                        double thisScore = Math.abs(targetValue - iteration.waitTimes.sum());
                        if (thisScore < score) {
                            StringJoiner waitTimes = new StringJoiner("|");
                            iteration.waitTimes.forEach(w -> {
                                    waitTimes.add(String.format("%.1f", w / 60f));
                                    return true;
                            });
                            waits = waitTimes.toString();
                            transfer =  String.format("%.1f", routeSequence.stopSequence.transferTime(iteration) / 60f);
                            totalTime = String.format("%.1f", iteration.totalTime / 60f);
                            if (thisScore == 0) break;
                            score = thisScore;
                        }
                    }
                    String group = ""; // Reserved for future use
                    String[] row = ArrayUtils.addAll(
                            path, transfer, waits, totalTime, String.valueOf(nIterations), group
                    );
                    checkState(row.length == DATA_COLUMNS.length);
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

    /**
     * Wraps path and iteration details for JSON serialization
     */
    public static class PathIterations {
        public StreetTimesAndModes.StreetTimeAndMode access;
        public StreetTimesAndModes.StreetTimeAndMode egress;
        public Collection<RouteSequence.TransitLeg> transitLegs;
        public Collection<Iteration> iterations;

        PathIterations(RouteSequence pathTemplate, TransitLayer transitLayer, Collection<Iteration> iterations) {
            this.access = pathTemplate.stopSequence.access;
            this.egress = pathTemplate.stopSequence.egress;
            this.transitLegs = pathTemplate.transitLegs(transitLayer);
            this.iterations = iterations;
        }
    }

    /**
     * Returns human-readable details of path iterations, for JSON representation (e.g. in the UI console).
     */
    public List<PathIterations> getPathIterationsForDestination() {
        checkState(iterationsForPathTemplates.length == 1, "Paths were stored for multiple " +
                "destinations, but only one is being requested");
        List<PathIterations> detailsForDestination = new ArrayList<>();
        Multimap<RouteSequence, Iteration> iterationMap = iterationsForPathTemplates[0];
        if (iterationMap != null) {
            for (RouteSequence pathTemplate : iterationMap.keySet()) {
                detailsForDestination.add(new PathIterations(pathTemplate, transitLayer,
                        iterationMap.get(pathTemplate).stream().sorted(Comparator.comparingInt(p -> p.departureTime))
                                .collect(Collectors.toList())
                ));
            }
        }
        return detailsForDestination;
    }

    /**
     * Temporal details of a specific iteration of our RAPTOR implementation (per-leg wait times and total time
     * implied by a specific departure time and randomized schedule offsets).
     */
    public static class Iteration {
        public int departureTime;
        public TIntList waitTimes;
        public int totalTime;

        public Iteration(Path path, int totalTime) {
            this.departureTime = path.departureTime;
            this.waitTimes = path.waitTimes;
            this.totalTime = totalTime;
        }

        /**
         * Constructor for paths with no transit boardings (and therefore no wait times).
         */
        public Iteration(int totalTime) {
            this.waitTimes = new TIntArrayList();
            this.totalTime = totalTime;
        }
    }
}
