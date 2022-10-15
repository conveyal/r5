package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.List;

public class TransitPathsPerIteration {
    // Access modes and transit layer are used for annotating paths.
    private StreetTimesAndModes bestAccessOptions;
    private final List<com.conveyal.r5.transit.path.Path[]> pathsPerIteration = new ArrayList<>();

    public final boolean isEnabled;

    public TransitPathsPerIteration(
            boolean isEnabled,
            StreetTimesAndModes bestAccessOptions
    ) {
        this.isEnabled = isEnabled;
        this.bestAccessOptions = bestAccessOptions;
    }

    /**
     * Get an array of paths for this iteration.
     */
    public com.conveyal.r5.transit.path.Path[] getIterationPaths(int i) {
        return pathsPerIteration.get(i);
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given RaptorState.
     */
    public void recordPathsForIterationState(RaptorState state) {
        if (!isEnabled) return;
        Preconditions.checkNotNull(bestAccessOptions, "Access options must be set before adding paths.");
        pathsPerIteration.add(getPathsForState(state));
    }

    private com.conveyal.r5.transit.path.Path[] getPathsForState(RaptorState state) {
        int nStops = state.bestNonTransferTimes.length;
        com.conveyal.r5.transit.path.Path[] paths = new com.conveyal.r5.transit.path.Path[nStops];
        for (int stopIndex = 0; stopIndex < nStops; stopIndex++) {
            if (state.bestNonTransferTimes[stopIndex] == FastRaptorWorker.UNREACHED) {
                paths[stopIndex] = null;
            } else {
                paths[stopIndex] = new com.conveyal.r5.transit.path.Path(
                        state,
                        stopIndex,
                        bestAccessOptions
                );
            }
        }
        return paths;
    }
}
