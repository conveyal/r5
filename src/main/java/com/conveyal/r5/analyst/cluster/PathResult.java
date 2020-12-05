package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransitLayer;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Holds and from an origin to destination target(s) at every Raptor iteration. For single-point  tasks, paths to
 * only a single destination (specified by toLon and toLat coordinates in the task) are recorded.
 *
 * This class is used to accumulate paths to be returned to the broker. In contrast, workers use the PathWriter to
 * write paths directly to S3 for Taui sites.
 */

public class PathResult {

    private final int nDestinations;
    private final int nIterations;
    /**
     * Map from each "path template" (i.e. a path ignoring per-iteration details such as wait time) to the associated
     * iteration details.
     */
    // TODO clean this up: maybe use a BasicPath (boardStopIds, alightStopIds, route or pattern Ids), extended by
    //  a Path (adds tripIds and associated boarding/alighting times), extended by a FullItinerary (includes specific
    //  iteration number/departure time, wait time details, etc.). Or just track full paths with the per-iteration
    //  details, if this deduplication isn't worth it.
    private final Multimap<Path, IterationDetails>[] itinerariesForPathTemplates;
    private final TransitLayer transitLayer;

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
        // TODO factor out into a method that could also be used setting reducer's timesPerDestination?
        this.nIterations = task.getTimeWindowLengthMinutes() * task.getMonteCarloDrawsPerMinute();
        itinerariesForPathTemplates = new Multimap[nDestinations];
        this.transitLayer = transitLayer;
    }

    // At 2 million destinations and 100 int values per destination (every percentile) we still only are at 800MB.
    // So no real risk of overflowing an int index.
    public void setTarget(int targetIndex, Multimap<Path, IterationDetails> pathsToTarget) {
        itinerariesForPathTemplates[targetIndex] = pathsToTarget;
    }

    public static class IterationDetails {
        public int iteration;
        public int waitTime;
        public int inVehicleTime;
        public int totalTime;

        public IterationDetails(Path path, int iteration, int totalTime) {
            this.iteration = iteration;
            this.waitTime = path.waitTime;
            this.inVehicleTime = path.inVehicleTime;
            this.totalTime = totalTime;
        }

    }

    /**
     * Summary of paths and associated wait, in-vehicle, and total times. Array is keyed on
     * destination index, iteration number, and result type:
     *
     * 0: path summary (using GTFS ids), reduced to board stop/alight stop/route details and ignoring which
     * specific trips are used
     * 1: accumulated wait time (in seconds)
     * 2: accumulated in-vehicle time (in seconds)
     * 3: total time (in seconds)
     *
     * Note that for a single iteration, the combined on-street time (access/transfer/egress) can be calculated as the
     * total time minus the wait and in-vehicle times.
     *
     * This may not be an efficient way to send path details over the wire, but it is designed to make it easy for
     * the backend server's result assembler to write a CSV when receiving results from multiple workers.
     */
    public String[][][] getSummaryOfIterations() {
        String[][][] summary = new String[nDestinations][nIterations][4];
        for (int d = 0; d < nDestinations; d++){
            Multimap<Path, IterationDetails> itineraries = itinerariesForPathTemplates[d];
            if (itineraries != null) {
                for (Path pathTemplate : itineraries.keySet()) {
                    String pathSummary = pathTemplate.toItineraryString(transitLayer);
                    for (IterationDetails itinerary : itineraries.get(pathTemplate)) {
                        summary[d][itinerary.iteration][0] = pathSummary;
                        summary[d][itinerary.iteration][1] = String.valueOf(itinerary.waitTime);
                        summary[d][itinerary.iteration][2] = String.valueOf(itinerary.inVehicleTime);
                        summary[d][itinerary.iteration][3] = String.valueOf(itinerary.totalTime);
                    }
                }
            }
        }
        return summary;
    }

    public static class PathIterations {
        public String[] pathSummary;
        public Collection<IterationDetails> iterations;

        PathIterations(String[] pathSummary, Collection<IterationDetails> iterations) {
            this.pathSummary = pathSummary;
            this.iterations = iterations;
        }
    }

    /**
     * Returns a summary of path iterations suitable for JSON representation.
     */
    List<PathIterations> getSummaryForDestination() {
        Preconditions.checkState(itinerariesForPathTemplates.length == 1, "Paths were stored for multiple destinations, but " +
                "only one is being requested");
        List<PathIterations> summaryToDestination = new ArrayList<>();
        if (itinerariesForPathTemplates[0] != null) {
            for (Path path : itinerariesForPathTemplates[0].keySet()) {
                String[] pathSummary = path.toTripString(transitLayer);
                summaryToDestination.add(new PathIterations(pathSummary, itinerariesForPathTemplates[0].get(path)));
            }
        }
        return summaryToDestination;
    }
}