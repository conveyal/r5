package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.transit.path.PathTemplate;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
        public int initialWait;
        public int waitTime;
        public int totalTime;

        public Iteration(Path path, int totalTime) {
            this.departureTime = path.departureTime;
            this.initialWait = path.initialWait;
            this.waitTime = path.waitTime;
            this.totalTime = totalTime;
        }

        public HumanReadableIteration humanReadable() {
            return new HumanReadableIteration(this);
        }

    }

    public static class HumanReadableIteration {
        public String departureTime;
        public float waitTime;
        public float totalTime;

        HumanReadableIteration(Iteration iteration) {
            this.departureTime =
                    String.format("%02d:%02d", Math.floorDiv(iteration.departureTime, 3600),
                            (int) (iteration.departureTime / 60.0 % 60));
            this.waitTime =  iteration.waitTime / 60f;
            this.totalTime =  iteration.totalTime / 60f;
        };
    }

    // TODO throw error if this method is called for tasks with Monte Carlo draws
    public ArrayList<String[]>[] getMinimumForPathIterations() {
        ArrayList<String[]>[] summary = new ArrayList[nDestinations];
        for (int d = 0; d < nDestinations; d++) {
            summary[d] = new ArrayList<>();
            Multimap<Path, IterationDetails> itinerariesForPathTemplate = itinerariesForPathTemplates[d];
            if (itinerariesForPathTemplate != null) {
                for (Path pathTemplate : itinerariesForPathTemplate.keySet()) {
                    String pathSummary = pathTemplate.toItineraryString(transitLayer);
                    Collection<IterationDetails> itineraries = itinerariesForPathTemplate.get(pathTemplate);
                    String nIterations = String.valueOf(itineraries.size());
                    IterationDetails latestIteration = (IterationDetails) itineraries.toArray()[0];
                    summary[d].add(ArrayUtils.addAll(
                            new String[]{pathSummary, nIterations},
                            latestIteration.timesToString()
                    ));
                }
            }
        }
        return summary;
    }

    // TODO merge with previous, using min/average enum as a parameter
    public ArrayList<String[]>[] getAverageForPathIterations() {
        ArrayList<String[]>[] summary = new ArrayList[nDestinations];
        for (int d = 0; d < nDestinations; d++) {
            summary[d] = new ArrayList<>();
            Multimap<Path, IterationDetails> itinerariesForPathTemplate = itinerariesForPathTemplates[d];
            if (itinerariesForPathTemplate != null) {
                for (Path pathTemplate : itinerariesForPathTemplate.keySet()) {
                    String pathSummary = pathTemplate.toItineraryString(transitLayer);
                    Collection<IterationDetails> itineraries = itinerariesForPathTemplate.get(pathTemplate);
                    String nIterations = String.valueOf(itineraries.size());
                    String avgWaitTime =
                            String.valueOf(itineraries.stream().mapToDouble(i -> i.waitTime).average().orElse(-1));
                    String inVehicleTime =
                            String.valueOf(itineraries.stream().mapToDouble(i -> i.inVehicleTime).average().orElse(-1));
                    String avgTotalTime =
                            String.valueOf(itineraries.stream().mapToDouble(i -> i.totalTime).average().orElse(-1));
                    summary[d].add(new String[]{pathSummary, nIterations, avgWaitTime, inVehicleTime, avgTotalTime});
                }
            }
        }
        return summary;
    }

    public static class PathIterations {
        public String[] pathSummary;
        public Collection<HumanReadableIteration> iterations;

        PathIterations(String[] pathSummary, Collection<Iteration> iterations) {
            this.pathSummary = pathSummary;
            this.iterations = iterations.stream().map(Iteration::humanReadable).collect(Collectors.toList());
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
                String[] pathSummary = path.toTripString(transitLayer);
                summaryToDestination.add(new PathIterations(pathSummary,
                        iterations.get(path).stream().sorted(Comparator.comparingInt(p -> p.departureTime)).collect(Collectors.toList())));
            }
        }
        return summaryToDestination;
    }
}