package com.conveyal.r5;

import com.conveyal.r5.analyst.AccessibilityResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.TimeGrid;

/**
 * This provides a single return type for all the kinds of results we can get from a travel time computer and reducer
 * for a single origin point:
 * Travel times to all destination cells, accessibility indicator values for various travel time cutoffs and percentiles
 * of travel time, travel time breakdowns into wait and ride and walk time, and paths to all destinations.
] */
public class OneOriginResult {

    public final TimeGrid timeGrid;

    public final AccessibilityResult accessibility;

    public OneOriginResult(TimeGrid timeGrid, AccessibilityResult accessibility) {
        this.timeGrid = timeGrid;
        this.accessibility = accessibility;
    }

    // Convert the accessibility results for this origin into a RegionalWorkResult.
    // This is a stopgap adapter that should eventually be eliminated. There's no real reason for two different
    // accessibility classes to exist. AccessibilityResult and RegionalWorkResult should probably be merged into one
    // class, they were just created in parallel to serve essentially the same purpose.
    // Or, maybe we should just nest an accessibilityResult inside the RegionalWorkResult, which just adds task ID info.
    public RegionalWorkResult toRegionalWorkResult(AnalysisTask task) {
        RegionalWorkResult result;
        if (accessibility == null) {
            // Stopgap: when doing static sites, make an empty 1x1x1 result just to signal work progress to the backend.
            result = new RegionalWorkResult(task.jobId, task.taskId, 1, 1, 1);
        } else {
            result = new RegionalWorkResult(task.jobId, task.taskId, accessibility.grids.length,
                    accessibility.percentiles.length, accessibility.cutoffs.length);
            result.setAcccessibilityValue(0, 0, 0, (int) accessibility.getAccessibility(0, 0, 0));
        }
        return result;
    }

}
