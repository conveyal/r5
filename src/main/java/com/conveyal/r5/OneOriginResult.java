package com.conveyal.r5;

import com.conveyal.r5.analyst.AccessibilityResult;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.PathResultsRecorder;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;

/**
 * This provides a single return type (for internal R5 use) for all the kinds of results we can get from a travel time
 * computer and reducer for a single origin point. Currently, these results include travel times to points in a
 * destination pointset, and accessibility indicator values for various travel time cutoffs and percentiles of travel
 * time.
 *
 * TODO add fields to record travel time breakdowns into wait and ride and walk time.
 */
public class OneOriginResult {

    public final TravelTimeResult travelTimes;

    public final AccessibilityResult accessibility;

    public final PathResultsRecorder paths;

    public OneOriginResult(TravelTimeResult travelTimes, AccessibilityResult accessibility, PathResultsRecorder paths) {
        this.travelTimes = travelTimes;
        this.accessibility = accessibility;
        this.paths = paths;
    }

    public PathResult[] getPathResults() {
        if (paths == null) return null;
        return paths.getPathResults();
    }

}
