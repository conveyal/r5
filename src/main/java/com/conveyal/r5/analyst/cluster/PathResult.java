package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.conveyal.r5.transit.path.PatternSequence;
import com.conveyal.r5.transit.path.RouteSequence;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
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

import static com.conveyal.r5.common.Util.newFixedSizeList;
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
     * In case the scenario includes multiple select-link modifications, for each selected link we store separate
     * groups of iterations at each destination.
     */
    private List<Multimap<RouteSequence, Iteration>[]> iterationsForDestinationForSelectedLink;

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
        // Only strictly necessary for select-link case, but makes reporting process more uniform in the general case.
        nUnfilteredIterationsReachingDestination = new int[nDestinations];
        // Only allocate this large 2D list-of-arrays when select-link modifications are present.
        if (transitLayer.parentNetwork.selectedLinks != null) {
            iterationsForDestinationForSelectedLink = newFixedSizeList(
                    transitLayer.parentNetwork.selectedLinks.size(),
                    () -> new Multimap[nDestinations]
            );
        }
        this.transitLayer = transitLayer;
    }

    /**
     * Populate the multimap of path templates to iterations, reducing by using route-based keys instead of
     * pattern-based keys.
     * TODO maybe we should be converting this to its final CSV form in a streaming manner instead of storing it.
     */
    public void setTarget(int targetIndex, Multimap<PatternSequence, Iteration> patterns) {
        // Only strictly necessary for select-link case, but makes reporting process more uniform in the general case.
        nUnfilteredIterationsReachingDestination[targetIndex] = patterns.size();
        // When selected link analysis is enabled, filter down the PatternSequence-Iteration Multimap to retain only
        // those keys passing through the selected links.
        // Maybe selectedLink instance should be on TransitLayer not TransportNetwork.
        final var selectedLinks = transitLayer.parentNetwork.selectedLinks;
        if (selectedLinks != null) {
            // The size of a multimap is the number of mappings (number of values), not number of unique keys.
            // This size method appears to be O(1), see: com.google.common.collect.AbstractMapBasedMultimap.size
            for (int i = 0; i < selectedLinks.size(); i++) {
                var selectedLink = selectedLinks.get(i);
                var iterationsForDestination = iterationsForDestinationForSelectedLink.get(i);
                iterationsForDestination[targetIndex] = collapsePatternsToRoutes(selectedLink.filterPatterns(patterns));
            }
            // Combining all the iterations from many select-links is tricky or nonsensical. They represent different
            // locations with different proportions of iterations passing through them, possibly grouped differently
            // into routes. It's probably best to list the non-summed iterations separately for each select-link.
        } else {
            // Standard case: no SelectedLink filtered down the patterns-iterations map. The elements of this array
            // will be left null if the filtering did happen, clearly distinguishing between the two cases.
            iterationsForPathTemplates[targetIndex] = collapsePatternsToRoutes(patterns);
        }
    }

    /**
     * Given a map from PatternSequences to the Iterations that used those sequences of patterns, merge the map
     * entries according to which route the patterns belong to.
     * The only thing preventing this method from being static is that it uses the transitLayer to look up route names.
     */
    private Multimap<RouteSequence, Iteration> collapsePatternsToRoutes (
            Multimap<PatternSequence, Iteration> patternIterations
    ) {
        // This could probably be replaced with ImmutableListMultimap.toImmutableListMultimap collector.
        final Multimap<RouteSequence, Iteration> routeIterations = HashMultimap.create();
        patternIterations.forEach(((patternSeq, iteration) ->
                routeIterations.put(new RouteSequence(patternSeq, transitLayer), iteration))
        );
        return routeIterations;
    }

    /**
     * Summary of iterations for each destination, suitable for writing to a CSV. Conversion to strings happens here
     * (on distributed workers) to minimize pressure on the central Broker's assembler.
     * TODO this would probably benefit from factoring out a bunch of named record types (return and parameter types).
     *
     * @param stat whether the reported per-leg wait times should correspond to the iteration with the minimum or mean
     *             total waiting time.
     * @return For each destination, a list of String[]s summarizing path templates. For each path template, details
     *          of the itinerary with waiting time closest to the requested stat are included.
     */
    public ArrayList<String[]>[] summarizeIterations (Stat stat) {
        ArrayList<String[]>[] csvRowsForDestinations = new ArrayList[nDestinations];
        final var selectedLinks = transitLayer.parentNetwork.selectedLinks;
        // For each destination, build up a list of CSV rows representing iteration paths that reached that destination.
        // Iteration order (over destinations, then selected links) is dictated by distributed computation. Workers
        // are working on one origin at a time, so while we might prefer the final file to be ordered by selected link,
        // without a final sort of the entire file the selected links would end up interleaved inside each origin.
        for (int d = 0; d < nDestinations; d++) {
            if (selectedLinks != null) {
                ArrayList<String[]> csvRows = new ArrayList<>();
                for (int s = 0; s < selectedLinks.size(); s++) {
                    var selectedLink = selectedLinks.get(s);
                    var iterationsMap = iterationsForDestinationForSelectedLink.get(s)[d];
                    var summaryRow = iterationsToSummaryCsvRow(iterationsMap, d, selectedLink.label);
                    if (summaryRow != null) {
                        csvRows.add(summaryRow);
                        csvRows.addAll(iterationsToCsvRows(iterationsMap, d, selectedLink.label, stat));
                    }
                }
                csvRowsForDestinations[d] = csvRows;
            } else {
                csvRowsForDestinations[d] = iterationsToCsvRows(iterationsForPathTemplates[d], d, null, stat);
            }
        }
        return csvRowsForDestinations;
    }

    /**
     * SelectedLink case: collapse all RouteSequences and Iterations for this OD pair into one simple summary.
     * patternIteration is empty (not null) for destinations that were reached without using the selected link.
     * Looks like array may be non-null but with null values if transit search fails (no origin linkage).
     * Returns null if there are no iterations here, indicating that for this selected link, this destination need not
     * be included in the CSV output.
     * The only thing preventing this method from being static is that it uses the transitLayer to look up route names.
     */
    private String[] iterationsToSummaryCsvRow (
        Multimap<RouteSequence, Iteration> patternIterations, int destinationIndex, String selectedLinkLabel
    ) {
        // If no transit search occurred, the iterations map may be null rather than empty.
        // We could entirely skip the CSV writing in this case, but full (non-select-link) results may want
        // output rows for every OD pair even when the origin did not use any transit.
        if (patternIterations == null) {
            return null;
        }
        int nIterations = 0;
        TIntSet allRouteIds = new TIntHashSet();
        double summedTotalTime = 0;
        for (RouteSequence routeSequence : patternIterations.keySet()) {
            Collection<Iteration> iterations = patternIterations.get(routeSequence);
            nIterations += iterations.size();
            allRouteIds.addAll(routeSequence.routes);
            summedTotalTime += iterations.stream().mapToInt(i -> i.totalTime).sum();
        }
        // Many destinations will have no iterations at all passing through the SelectedLink area.
        // Skip those to keep the CSV output short (and to avoid division by zero below).
        if (nIterations == 0) {
            return null;
        }
        String[] row = new String[DATA_COLUMNS.length];
        Arrays.fill(row, "ALL");
        String allRouteIdsPipeSeparated = Arrays.stream(allRouteIds.toArray())
                // If includeName is set to false we record only the ID without the name.
                // Name works better than ID for routes added by modifications, which have random IDs.
                .mapToObj(ri -> transitLayer.routeString(ri, true))
                .collect(Collectors.joining("|"));
        String iterationProportion = "%.3f".formatted(
            nIterations / (double) (nUnfilteredIterationsReachingDestination[destinationIndex])
        );
        // FIXME delimiter inside string is only acceptable as a temporary solution to add a final column without a header.
        // Can't inject a comma because the CSV writer library will quote escape it.
        row[0] = selectedLinkLabel + "; TOTAL " + allRouteIdsPipeSeparated;
        row[row.length - 1] = iterationProportion;
        // Report average of total time over all retained iterations, different than mean/min approach below.
        row[row.length - 2] = String.format("%.1f", summedTotalTime / nIterations / 60d);
        return row;
    }

    /**
     * Standard (non SelectedLink) case.
     * Factored out to allow repeated calls generating multiple sets of CSV rows when multiple select-links present.
     * The only thing preventing this method from being static is that it uses the transitLayer to look up route names.
     */
    private ArrayList<String[]> iterationsToCsvRows (
        Multimap<RouteSequence, Iteration> iterationMap, int destinationIndex, String selectedLinkLabel, Stat stat
    ) {
        // Hold all the summary CSV rows for this particular destination.
        ArrayList<String[]> summary = new ArrayList<>();
        // When some destinations are reached, any unreached ones are empty maps rather than nulls. They will be
        // null if no search happens at all (origin is not connected to network) in which case maybe we should skip
        // this step.
        if (iterationMap == null) {
            return summary; // FIXME return type does not allow List.of() or EMPTY_LIST;
        }
        // Make and stores an array of CSV rows as arrayLists, one for each destination.
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
                    nIterations / (double)(nUnfilteredIterationsReachingDestination[destinationIndex]));
            // FIXME delimiter inside string is a temporary solution to add a final column without a header.
            // Can't inject a comma because the CSV writer library will quote escape it.
            if (selectedLinkLabel != null) {
                path[0] = selectedLinkLabel + "; " + path[0];
            }
            String[] row = ArrayUtils.addAll(path, transfer, waits, totalTime, iterationProportion);
            checkState(row.length == DATA_COLUMNS.length);
            summary.add(row);
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
