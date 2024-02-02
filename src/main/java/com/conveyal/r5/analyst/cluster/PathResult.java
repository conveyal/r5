package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.conveyal.r5.transit.path.PatternSequence;
import com.conveyal.r5.transit.path.RouteSequence;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

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

    /**
     * The total number of iterations that reached each destination can be derived from iterationsForPathTemplates
     * as long as every path is being retained. When filtering down to a subset of paths, such as only those passing
     * through a selected link, we need this additional array to retain the information.
     */
    private int[] nUnfilteredIterationsReachingDestination;

    /**
     * In case the scenario includes multiple select-link modifications, we store separate groups of patterns
     * for each selected link, at each destination.
     */
    private List<Multimap<RouteSequence, Iteration>>[] iterationsForEachSelectedLink;

    private final TransitLayer transitLayer;

    public static final String[] DATA_COLUMNS = new String[]{
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
        // Only allocate these large arrays when select-link modifications are present.
        if (transitLayer.parentNetwork.selectedLinks != null) {
            nUnfilteredIterationsReachingDestination = new int[nDestinations];
            iterationsForEachSelectedLink = new ArrayList[nDestinations];
        }
        this.transitLayer = transitLayer;
    }

    /**
     * Populate the multimap of path templates to iterations, reducing by using route-based keys instead of
     * pattern-based keys
     */
    public void setTarget(int targetIndex, Multimap<PatternSequence, Iteration> patterns) {
        // When selected link analysis is enabled, filter down the PatternSequence-Iteration Multimap to retain only
        // those keys passing through the selected links.
        // Maybe selectedLink instance should be on TransitLayer not TransportNetwork.
        if (transitLayer.parentNetwork.selectedLinks != null) {
            // The size of a multimap is the number of mappings (number of values), not number of unique keys.
            // This size method appears to be O(1), see: com.google.common.collect.AbstractMapBasedMultimap.size
            nUnfilteredIterationsReachingDestination[targetIndex] = patterns.size();
            // Unfortunate Java "effectively final" silliness, but older iteration style gets ugly here too.
            final var finalPatterns = patterns;
            iterationsForEachSelectedLink[targetIndex] = transitLayer.parentNetwork.selectedLinks.stream()
                    .map(selectedLink -> selectedLink.filterPatterns(finalPatterns))
                    .map(this::collapsePatternsToRoutes)
                    .collect(Collectors.toList());
            // Combining all the iterations from many select-links is tricky or nonsensical. They represent different
            // locations with different proportions of iterations passing through them, possibly grouped differently
            // into routes. It's probably best to list the non-summed iterations separately for each select-link.
        } else {
            // Standard case: no SelectedLink filtered down the patterns-iterations map. These maps will be left
            // null if the filtering did happen, to clearly distinguish between the two cases.
            iterationsForPathTemplates[targetIndex] = collapsePatternsToRoutes(patterns);
        }
    }

    // Cannot be static as it uses the transitlayer field to look up routes.
    private Multimap<RouteSequence, Iteration> collapsePatternsToRoutes (
            Multimap<PatternSequence, Iteration> patterns
    ) {
        final Multimap<RouteSequence, Iteration> routes = HashMultimap.create();
        patterns.forEach(((patternSeq, iteration) -> routes.put(new RouteSequence(patternSeq, transitLayer), iteration)));
        return routes;
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
            // Hold all the summary CSV rows for this particular destination
            summary[d] = new ArrayList<>();
            // SelectedLink case: collapse all RouteSequences and Iterations for this OD pair into one to simplify.
            // iterationMap is empty (not null) for destinations that were reached without using the selected link.
            // This could also be done by merging all Iterations under a single RouteSequence with all route IDs.
            // Looks like array may be non-null but with null values if transit search fails (no origin linkage).
            if (iterationsForEachSelectedLink != null && iterationsForEachSelectedLink[d] != null) {
                // TODO transpose to list of arrays instead of array of lists, factor selectedLink iteration outside this method call
                int n = 0; // TODO handle this list-zip better (Streams.zip())
                for (var patternsForSelectedLink : iterationsForEachSelectedLink[d]) {
                    String selectedLinkLabel = transitLayer.parentNetwork.selectedLinks.get(n++).label;
                    int nIterations = 0;
                    TIntSet allRouteIds = new TIntHashSet();
                    double summedTotalTime = 0;
                    for (RouteSequence routeSequence : patternsForSelectedLink.keySet()) {
                        Collection<Iteration> iterations = patternsForSelectedLink.get(routeSequence);
                        nIterations += iterations.size();
                        allRouteIds.addAll(routeSequence.routes);
                        summedTotalTime += iterations.stream().mapToInt(i -> i.totalTime).sum();
                    }
                    // Many destinations will have no iterations at all passing through the SelectedLink area.
                    // Skip those to keep the CSV output short (and to avoid division by zero below).
                    if (nIterations == 0) {
                        continue;
                    }
                    String[] row = new String[DATA_COLUMNS.length];
                    Arrays.fill(row, "ALL");
                    transitLayer.routeString(1, true);
                    String allRouteIdsPipeSeparated = Arrays.stream(allRouteIds.toArray())
                            // If includeName is set to false we record only the ID without the name.
                            // Name works better than ID for routes added by modifications, which have random IDs.
                            .mapToObj(ri -> transitLayer.routeString(ri, true))
                            .collect(Collectors.joining("|"));
                    String iterationProportion = "%.3f".formatted(
                            nIterations / (double) (nUnfilteredIterationsReachingDestination[d]));
                    row[0] = selectedLinkLabel + ": " + allRouteIdsPipeSeparated;
                    row[row.length - 1] = iterationProportion;
                    // Report average of total time over all retained iterations, different than mean/min approach below.
                    row[row.length - 2] = String.format("%.1f", summedTotalTime / nIterations / 60d);
                    summary[d].add(row);
                    // TODO call the standard case below, so the summary row is followed by its component parts.
                    // We could optionally continue to the next outer for loop iteration here, to return only the summary row.
                }
            }
            // Standard (non SelectedLink) case.
            Multimap<RouteSequence, Iteration> iterationMap = iterationsForPathTemplates[d];
            // It looks like this could just be: if (iterationmap == null) continue; (to clarify without indentation)
            // When some destinations are reached, any unreached ones are empty maps rather than nulls. They will be
            // null if no search happens at all (origin is not connected to network) in which case maybe we should skip
            // this step. This makes and stores an array of CSV rows as arrayLists, one for each destination.
            if (iterationMap != null) {
                for (RouteSequence routeSequence: iterationMap.keySet()) {
                    Collection<Iteration> iterations = iterationMap.get(routeSequence);
                    int nIterations = iterations.size();
                    checkState(nIterations > 0, "A path was stored without any iterations");
                    String waits = null, transfer = null, totalTime = null;
                    String[] path = routeSequence.detailsWithGtfsIds(transitLayer, true);
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
                    // Check above guarantees that nIterations is nonzero, so total iterations must be nonzero,
                    // avoiding divide by zero.
                    String iterationProportion = "%.3f".formatted(
                            nIterations / (double)(nUnfilteredIterationsReachingDestination[d]));
                    String[] row = ArrayUtils.addAll(path, transfer, waits, totalTime, iterationProportion);
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
