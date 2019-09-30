package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.PointSet;

/**
 * Represents a task to be performed as part of a regional analysis.
 */
public class RegionalTask extends AnalysisTask implements Cloneable {

    /**
     * Coordinates of origin cell in grid defined in AnalysisTask.
     */
    public int x = -1, y = -1;

    /**
     * The pointset key (e.g. regionId/datasetId.grid) on S3 to compute access to. Still named grid (instead of
     * destinationPointSetKey for backward compatibility, namely the ability to start regional jobs on old worker
     * versions).
     *
     * If this is not blank, the default TravelTimeSurfaceTask  will be overridden; returnInVehicleTimes,
     * returnWaitTimes, and returnPaths will be set to false; and the returned results will be an accessibility value
     * per origin, rather than a grid of travel times from that origin. // TODO revise and improve this explanation
     */
    public String grid;

    /**
     * The pointset we are calculating accessibility to. This is not serialized into the request, it's looked up by the
     * worker.
     */
    public transient PointSet destinationPointSet;

    /**
     * Key for pointset (e.g. regionId/datasetId.pointset) from which to calculate travel times or accessibility
     */
    public String originPointSetKey;

    /**
     * Whether to calculate travel time from each origin to one corresponding destination (the destination at the
     * same position in the destionationPointSet). If false, travel time calculations will be many-to-many (between
     * all origin points and all destination points).
     */
    public boolean oneToOne = false;

    /**
     * Whether to record travel times between origins and destinations
     */
    public boolean recordTimes;

    /**
     * Whether to record cumulative opportunity accessibility indicators for each origin
     */
    public boolean recordAccessibility;

    /**
     * Is set explicitly when freeform pointset supplied as origin; otherwise 0
     */
    public int nTravelTimeTargetsPerOrigin;

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
