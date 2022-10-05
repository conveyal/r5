package com.conveyal.r5.analyst;

import com.conveyal.r5.transit.IterationTemporalDetails;
import com.conveyal.r5.transit.PatternSequence;
import com.conveyal.r5.transit.RouteSequence;
import com.conveyal.r5.transit.SerializablePathIterations;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
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
    public static int maxDestinations = 5000;

    private final int nDestinations;
    /**
     * Array with one entry per destination. Each entry is a map from a "path template" to the associated iteration
     * details. For now, the path template is a route-based path ignoring per-iteration details such as wait time.
     * With additional changes, patterns could be collapsed further to route combinations or modes.
     */
    private final Multimap<RouteSequence, IterationTemporalDetails>[] iterationsForPathTemplates;
    private final TransitLayer transitLayer;

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

    public PathResult(TransitLayer transitLayer, int nDestinations) {
        this.nDestinations = nDestinations;
        iterationsForPathTemplates = new Multimap[nDestinations];
        this.transitLayer = transitLayer;
    }

    /**
     * Populate the multimap of path templates to iterations, reducing by using route-based keys instead of
     * pattern-based keys
     */
    public void setTarget(int targetIndex, Multimap<PatternSequence, IterationTemporalDetails> patterns) {
        Multimap<RouteSequence, IterationTemporalDetails> routes = HashMultimap.create();
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
            Multimap<RouteSequence, IterationTemporalDetails> iterationMap = iterationsForPathTemplates[d];
            if (iterationMap != null) {
                for (RouteSequence routeSequence: iterationMap.keySet()) {
                    Collection<IterationTemporalDetails> iterations = iterationMap.get(routeSequence);
                    int nIterations = iterations.size();
                    checkState(nIterations > 0, "A path was stored without any iterations");
                    String waits = null, transfer = null, totalTime = null;
                    String[] path = routeSequence.detailsWithGtfsIds(transitLayer);
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
                    for (IterationTemporalDetails iteration: iterations) {
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
                    String[] row = ArrayUtils.addAll(path, transfer, waits, totalTime, String.valueOf(nIterations));
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
     * Returns human-readable details of path iterations, for JSON representation (e.g. in the UI console).
     */
    public List<SerializablePathIterations> getPathIterationsForDestination() {
        checkState(iterationsForPathTemplates.length == 1, "Paths were stored for multiple " +
                "destinations, but only one is being requested");
        List<SerializablePathIterations> detailsForDestination = new ArrayList<>();
        Multimap<RouteSequence, IterationTemporalDetails> iterationMap = iterationsForPathTemplates[0];
        if (iterationMap != null) {
            for (RouteSequence pathTemplate : iterationMap.keySet()) {
                detailsForDestination.add(new SerializablePathIterations(pathTemplate, transitLayer,
                        iterationMap.get(pathTemplate).stream().sorted(Comparator.comparingInt(p -> p.departureTime))
                                .collect(Collectors.toList())
                ));
            }
        }
        return detailsForDestination;
    }

}
