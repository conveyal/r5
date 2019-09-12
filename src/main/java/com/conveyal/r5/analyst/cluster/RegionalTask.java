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
     * The pointset key (e.g. regionId/datasetId.grid) on S3 to compute access to. If this is not blank, the default
     * TravelTimeSurfaceTask  will be overridden; returnInVehicleTimes, returnWaitTimes, and returnPaths will be set
     * to false; and the returned results will be an accessibility value per origin, rather than a grid of travel
     * times from that origin. // TODO replace with list
     */
    public String destinationPointSetKey;
    
    /**
     * The pointset we are calculating accessibility to. This is not serialized into the request, it's looked up by the
     * worker.
     */
    public transient PointSet destinationPointSet;

    /**
     * Key for pointset (e.g. regionId/datasetId.pointset) to use as origins.
     */
    public String originPointSetKey;

    /**
     * Is explicitly when freeform pointset supplied as origin; otherwise 0
     */
    public int nTravelTimeTargetsPerOrigin;

    /**
     * Whether to calculate travel times from each origin to the one destination with matching id. If false, travel
     * time results will be an array of travel times to all destinations. Not yet tested; implementation will also
     * need to set this based on an incoming AnalysisRequest.
     */
    public boolean oneToOne;

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
