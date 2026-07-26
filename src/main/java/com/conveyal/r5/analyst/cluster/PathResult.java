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

import static com.conveyal.r5.common.Util.secondsToHhMm;
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
            "departureTime",
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

    /// Summary of iterations for each destination, suitable for writing to a CSV. Conversion to strings happens here
    /// (on distributed workers) to minimize pressure on the central Broker's assembler.
    ///
    /// For each destination, a row is produced for each distinct route-based path template, summarizing all the
    /// iterations (departure minutes and Monte Carlo schedules) in which that template was optimal. The fixed
    /// components of a template (access, egress, ride, and transfer times) are identical in all its iterations.
    /// Only the waiting times and the total travel time vary. The requested Stat determines how those varying
    /// quantities are summarized:
    ///
    /// - MINIMUM reports the single fastest iteration. The row describes an itinerary a rider could actually follow,
    ///   and its columns sum exactly. Total time equals access + egress + rides + transfers + waits.
    ///
    /// - MEAN reports each leg's waiting time averaged over all iterations and the average total time. Because the
    ///   other components do not vary, these averages also sum exactly. The mean total time equals access + egress +
    ///   rides + transfers + mean waits. No single iteration necessarily matches, so departure time is left empty.
    public ArrayList<String[]>[] summarizeIterations(Stat stat) {
        ArrayList<String[]>[] summary = new ArrayList[nDestinations];
        for (int d = 0; d < nDestinations; d++) {
            summary[d] = new ArrayList<>();
            Multimap<RouteSequence, Iteration> iterationMap = iterationsForPathTemplates[d];
            if (iterationMap == null) continue;
            for (RouteSequence routeSequence : iterationMap.keySet()) {
                Collection<Iteration> iterations = iterationMap.get(routeSequence);
                int nIterations = iterations.size();
                checkState(nIterations > 0, "A path was stored without any iterations");
                int nLegs = routeSequence.routes.size();
                String[] path = routeSequence.detailsWithGtfsIds(transitLayer, csvOptions);
                String transfer = formatMinutes(routeSequence.stopSequence.totalTransferTimeSeconds());
                String waits, totalTime, departureTime;
                if (stat == Stat.MINIMUM) {
                    // Report the fastest single iteration, an itinerary the rider could actually follow.
                    // The secondary comparison on departure time makes the choice among ties deterministic.
                    Iteration fastest = iterations.stream()
                            .min(Comparator.comparingInt((Iteration i) -> i.totalTime)
                                    .thenComparingInt(i -> i.departureTime))
                            .get();
                    StringJoiner waitJoiner = new StringJoiner("|");
                    fastest.waitTimes.forEach(w -> {
                        waitJoiner.add(formatMinutes(w));
                        return true;
                    });
                    waits = waitJoiner.toString();
                    totalTime = formatMinutes(fastest.totalTime);
                    // A departure time is only meaningful when transit is ridden.
                    // The total time of a walk-only trip does not depend on when it starts.
                    departureTime = (nLegs > 0) ? secondsToHhMm(fastest.departureTime) : "";
                } else if (stat == Stat.MEAN) {
                    // Report each leg's wait averaged over all iterations. The number of legs
                    // determined by grouping, so the wait lists of all iterations are parallel.
                    double[] waitSums = new double[nLegs];
                    double totalTimeSum = 0;
                    for (Iteration iteration : iterations) {
                        totalTimeSum += iteration.totalTime;
                        for (int leg = 0; leg < nLegs; leg++) {
                            waitSums[leg] += iteration.waitTimes.get(leg);
                        }
                    }
                    StringJoiner waitJoiner = new StringJoiner("|");
                    for (int leg = 0; leg < nLegs; leg++) {
                        waitJoiner.add(formatMinutes(waitSums[leg] / nIterations));
                    }
                    waits = waitJoiner.toString();
                    totalTime = formatMinutes(totalTimeSum / nIterations);
                    departureTime = "";
                } else {
                    throw new IllegalArgumentException("Unrecognized statistic for path summary");
                }
                String group = ""; // Reserved for future use
                String[] row = ArrayUtils.addAll(
                        path, transfer, waits, totalTime, departureTime, String.valueOf(nIterations), group
                );
                checkState(row.length == DATA_COLUMNS.length);
                summary[d].add(row);
            }
        }
        return summary;
    }

    /// Format a duration in seconds as fractional minutes with one decimal place, as used in the result CSV.
    private static String formatMinutes(double seconds) {
        return String.format("%.1f", seconds / 60d);
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

        public Iteration(Path path, int departureTime, int totalTime) {
            this.departureTime = departureTime;
            this.waitTimes = path.computeWaitTimes(departureTime);
            this.totalTime = totalTime;
        }

        /**
         * Constructor for iterations that ride no transit (and therefore have no wait times).
         */
        public Iteration(int departureTime, int totalTime) {
            this.departureTime = departureTime;
            this.waitTimes = new TIntArrayList();
            this.totalTime = totalTime;
        }
    }
}
