package com.conveyal.r5;

import com.conveyal.r5.analyst.AccessibilityAccumulator;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.CombinedWorkResult;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;

/**
 * This provides a single return type for all the kinds of results we can get from a travel time computer and reducer
 * for a single origin point:
 * Travel times to all destination cells, accessibility indicator values for various travel time cutoffs and percentiles
 * of travel time, travel time breakdowns into wait and ride and walk time, and paths to all destinations.
] */
public class OneOriginContainer {

    public String jobId;
    public int taskId;

    public final TravelTimeResult travelTimes;

    public final AccessibilityAccumulator accessibility;

    public OneOriginContainer(TravelTimeResult travelTimes, AccessibilityAccumulator accessibility) {
        this.travelTimes = travelTimes;
        this.accessibility = accessibility;
    }

    /**
     * Empty container, used for testing
     * @param task
     */
    public OneOriginContainer(AnalysisTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.travelTimes = null;
        this.accessibility = new AccessibilityAccumulator();
    }

    public CombinedWorkResult toResult() {
        return new CombinedWorkResult(this);
    }

    public OneOriginContainer setJobAndTaskIds(AnalysisTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        return this;
    }

}
