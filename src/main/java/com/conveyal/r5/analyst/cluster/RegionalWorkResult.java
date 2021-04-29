package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.util.ExceptionUtils;

import java.util.ArrayList;

/**
 * Model class used for serialized travel times and accessibility indicators for a multi-origin (regional) analysis,
 * sent over HTTP to the backend/broker. Similar to OneOriginResult, but with arrays for results (instead of more
 * descriptive objects) for compactness.
 */
public class RegionalWorkResult {

    public String jobId;
    public int taskId;

    /**
     * Values from a travelTimeResult, keyed on percentile of total travel time and target index.
     * Note that the broker's polling system was not designed to handle large amounts of data; travel time
     * results are currently an experimental feature and large numbers of targets may overwhelm the system.
     */
    public int[][] travelTimeValues;

    /**
     * String array summarizing the details of a path at a specific iteration (e.g. wait time, in-vehicle time), keyed
     * on target index and iteration.
     */
    public ArrayList<String[]>[] pathResult;

    /**
     * These are the truncated integer accessibility results for each [destinationGrid, percentile, cutoff].
     * We report accessibility for a particular travel time cutoff, with travel time defined as a particular percentile.
     * So the rows are the percentiles, and the columns are the accessibility values for particular cutoffs of that percentile of travel time.
     * There are also more cutoffs than percentiles, so given Java's 2D array representation this is more efficient.
     * TODO Should this be floating point?
     */
    public int[][][] accessibilityValues;

    /**
     * If this field is non-null, the worker is reporting an error that compromises the quality of the result at this
     * origin point, and potentially for then entire regional analysis. Put into a Set on backend since all workers
     * will probably report the same problem, but we may want to tolerate errors on a small number of origin points to
     * not waste computation. On the other hand any error here implies incorrect inputs, configuration, or software.
     */
    public String error;

    /** Trivial no-arg constructor for deserialization. Private to prevent usage outside deserialization. */
    private RegionalWorkResult() { }

    /**
     * Convert the supplied internal R5 OneOriginResult into this more compact form intended for serialization
     * and transfer from the worker back to the backend. The job and task ID are copied from the supplied task
     * to show which task these results are for.
     */
    public RegionalWorkResult(OneOriginResult result, RegionalTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.travelTimeValues = result.travelTimes == null ? null : result.travelTimes.values;
        this.accessibilityValues = result.accessibility == null ? null : result.accessibility.getIntValues();
        this.pathResult = result.paths == null ? null : result.paths.summarizeIterations(PathResult.Stat.MINIMUM);
        // TODO checkTravelTimeInvariants, checkAccessibilityInvariants to verify that values are monotonically increasing
    }

    /** Constructor used when results for this origin are considered unusable due to an unhandled error. */
    public RegionalWorkResult(Throwable t, RegionalTask task) {
        this.jobId = task.jobId;
        this.taskId = task.taskId;
        this.error = ExceptionUtils.shortAndLongString(t);
    }

}
