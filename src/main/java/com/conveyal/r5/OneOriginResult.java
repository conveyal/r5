package com.conveyal.r5;

import com.conveyal.r5.analyst.AccessibilityAccumulator;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;

/**
 * This provides a single return type (for internal R5 use) for all the kinds of results we can get from a travel time
 * computer and reducer for a single origin point. Currently, these results include travel times to points in a
 * destination pointset, and accessibility indicator values for various travel time cutoffs and percentiles of travel
 * time. TODO add fields to record travel time breakdowns into wait and ride and walk time, and paths to destinations.
 */
public class OneOriginResult {

    public String jobId;
    public int taskId;

    public final TravelTimeResult travelTimes;

    public final AccessibilityAccumulator accessibility;

    public OneOriginResult(TravelTimeResult travelTimes, AccessibilityAccumulator accessibility) {
        this.travelTimes = travelTimes;
        this.accessibility = accessibility;
    }

    /**
     * Empty container, used for testing
     * @param task
     */
    public OneOriginResult(AnalysisTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.travelTimes = null;
        this.accessibility = new AccessibilityAccumulator();
    }

    public RegionalWorkResult toResult() {
        return new RegionalWorkResult(this);
    }

    public OneOriginResult setJobAndTaskIds(AnalysisTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        return this;
    }

}
