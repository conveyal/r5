package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.profile.TransitPathsPerIteration;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.Path;

/**
 * Set up a way for path data to be recorded during routing and propagation. Recording is enabled based on the passed
 * in values. Methods may be called even if recording is not enabled. Flags do not need to be checked repeatedly while
 * using externally.
 */
public class PathResultsRecorder {
    /**
     * The maximum number of destinations for which we'll generate detailed path information in a single request.
     * Detailed path information was added on to the original design, which returned a simple grid of travel times.
     * These results are returned to the backend over an HTTP API so we don't want to risk making them too huge.
     * This could be set to a higher number in cases where you know the result return channel can handle the size.
     */
    private static int MAX_DESTINATIONS = 5_000;
    // In one-to-one or single point mode there will be a single target index to record.
    private final int targetIdxToRecord;

    // Number of iterations per target
    private final int nIterations;

    // The following two fields are initialized depending on if different path recording modes are enabled.
    private final PathResult[] pathResults;
    private final TransitPathsPerIteration pathsPerIteration;

    // Paths and egress modes are reset per target.
    private StreetTimeAndMode[] egressModes = null;
    private Path[] paths = null;

    // Transit layer required to convert patterns to route indexes
    private final TransitLayer transitLayer;

    public PathResultsRecorder(
            TransitLayer transitLayer,
            AnalysisWorkerTask task,
            boolean oneToOne,
            TransitPathsPerIteration pathsPerIteration,
            int targetIdxToRecord,
            int nIterations
    ) {
        this.transitLayer = transitLayer;
        this.pathResults = initializePathResultsFromTask(task, oneToOne);
        this.targetIdxToRecord = targetIdxToRecord;
        this.nIterations = nIterations;
        this.pathsPerIteration = pathsPerIteration;
    }

    /**
     * Helper to initialize a path results array depending on the configuration given.
     */
    static PathResult[] initializePathResultsFromTask(AnalysisWorkerTask task, boolean oneToOne) {
        int nDestinationResults = 0;
        if (task.includePathResults || task.makeTauiSite) {
            // In interactive single-point tasks, paths are only returned for one destination
            // In regional analyses, return paths to all destinations
            nDestinationResults = task instanceof TravelTimeSurfaceTask || oneToOne ? 1 : task.nTargetsPerOrigin();

            // This limitation reflects the initial design, for use with freeform pointset destinations
            if (nDestinationResults > MAX_DESTINATIONS) {
                throw new UnsupportedOperationException("Number of detailed path destinations exceeds limit of " + MAX_DESTINATIONS);
            }
        }
        return nDestinationResults > 0 ? new PathResult[nDestinationResults] : null;
    }

    /**
     * @return pathResults All accumulated path results for targets.
     */
    public PathResult[] getPathResults() {
        return pathResults;
    }

    /**
     * @return true if the paths recorder has a writer or a results array.
     */
    private boolean isEnabled() {
        return pathResults != null;
    }

    /**
     * @return true if we should record paths for a given target index.
     */
    private boolean shouldRecordPathsForTarget(int targetIdx) {
        if (!isEnabled()) return false;
        // If we are recording more than one result, no need to check the target ID.
        if (pathResults.length > 1) return true;
        // For oneToOne or single point tasks, ensure this is the requested target ID.
        return targetIdx == targetIdxToRecord;
    }

    public boolean isRecordingTarget() {
        return isEnabled() && egressModes != null && paths != null;
    }

    /**
     * Initialize the egress modes and stop indexes arrays if recording this target index is enabled.
     */
    public void startRecordingTarget(int targetIdx) {
        if (shouldRecordPathsForTarget(targetIdx)) {
            egressModes = new StreetTimeAndMode[nIterations];
            paths = new Path[nIterations];
        } else {
            egressModes = null;
            paths = null;
        }
    }

    /**
     * Set the stop index and mode for this iteration.
     */
    public void setTargetIterationValues(int iteration, int stopIndex, StreetTimeAndMode mode) {
        if (!isRecordingTarget()) return;
        Path path = pathsPerIteration.getIterationPaths(iteration)[stopIndex];
        if (path != null) {
            paths[iteration] = path;
            egressModes[iteration] = mode;
        }
    }

    /**
     * After accumulating the times, stops, and modes for each iteration of this target index we can record the paths.
     */
    public void recordPathsForTarget(
            int targetIdx,
            int[] travelTimes
    ) {
        if (!shouldRecordPathsForTarget(targetIdx)) return;
        pathResults[pathResults.length == 1 ? 0 : targetIdx] = new PathResult(
                transitLayer,
                travelTimes,
                paths,
                egressModes
        );
    }
}
