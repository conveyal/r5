package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.Grid;
import java.util.List;

/**
 * Represents a task to be performed as part of a regional analysis.
 */
public class RegionalTask extends AnalysisTask implements Cloneable {

    /**
     * Coordinates of origin cell in grid defined in AnalysisTask.
     *
     * Note that these do not override fromLat and fromLon; those must still be set separately. This is for future use
     * to allow use of arbitrary origin points.
     */
    public int x = -1, y = -1;

    /**
     * The grid key on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin, rather than a grid of travel times from that origin.
     */
    public String grid;

    /**
     * An array of grid keys on S3 to compute access to. If this is not blank, the default TravelTimeSurfaceTask will be
     * overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set to false; and the returned results
     * will be an accessibility value per origin for each destination grid, rather than a grid of travel times from
     * that origin.
     * NOT YET IMPLEMENTED AND TESTED
     */
    public List <String> grids;

    /** Where should output of this job be saved */
    public String outputQueue;

    /**
     * The grid we are calculating accessibility to. This is not serialized int the request, it's looked up by the worker.
     * TODO use distinct terms for grid extents and gridded opportunity density data.
     */
    public transient Grid gridData;

    @Override
    public Type getType() {
        return Type.REGIONAL_ANALYSIS;
    }

    @Override
    public boolean isHighPriority() {
        return false; // regional analysis tasks are not high priority
    }

    public RegionalTask clone () {
        return (RegionalTask) super.clone();
    }

    @Override
    public String toString() {
        // Having job ID and allows us to follow regional analysis progress in log messages.
        return "RegionalTask{" +
                "jobId=" + jobId +
                ", task=" + taskId +
                ", x=" + x +
                ", y=" + y +
                '}';
    }

}
