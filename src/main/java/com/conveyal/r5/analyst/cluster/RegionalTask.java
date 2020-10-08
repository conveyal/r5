package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.WebMercatorExtents;

/**
 * Represents a task to be performed as part of a regional analysis.
 * Instances are serialized and sent from the backend to workers when processing regional analyses.
 */
public class RegionalTask extends AnalysisWorkerTask implements Cloneable {

    /**
     * The storage key for the pointset we will compute access to (e.g. regionId/datasetId.grid).
     * This is named grid instead of destinationPointSetId for backward compatibility, namely the ability to start
     * regional jobs on old worker versions that expect the property "grid".
     *
     * Overloaded to specify a set of destination points which may or may not have densities attached.
     * In fact this ID is taken from a field called "opportunityDatasetId" in the request coming from the UI. So we've
     * got several slightly conflicting names and concepts.
     *
     * TODO revise and improve the below explanation:
     * If this is not blank, the default TravelTimeSurfaceTask will be overridden; returnInVehicleTimes,
     * returnWaitTimes, and returnPaths will be set to false; and the returned results will be an accessibility value
     * per origin, rather than a grid of travel times from that origin.
     */
    @Deprecated
    public String grid;

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

    @Override
    public Type getType() {
        return Type.REGIONAL_ANALYSIS;
    }

    /**
     * For Taui (static) sites, there is no fixed opportunity grid as accessibility is computed in the web UI.
     * The grid of destinations is the extents given in the task, which for Taui sites is also the grid of origins.
     *
     * For standard, non-Taui regional analyses, we expect at least one valid grid of opportunities to be specified as
     * the destinations, as this is necessary to compute accessibility. Travel times to any location outside those grids
     * cannot change accessibility results, and we are not displaying travel time isochrones, so we extract the
     * minimal bounds containing all destination opportunity grids. This is not optimal where the full extent of the
     * road network is smaller than the opportunity data, but that should be rare.
     */
    @Override
    public WebMercatorExtents getWebMercatorExtents() {
        if (makeTauiSite) {
            return WebMercatorExtents.forTask(this);
        } else {
            return WebMercatorExtents.forPointsets(this.destinationPointSets);
        }
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
                '}';
    }

    @Override
    public int nTargetsPerOrigin () {
        // In multi-origin regional tasks, the set of destinations may be determined by the exact kind of task
        if (oneToOne) {
            return 1;
        }  else if (makeTauiSite) {
            return width * height;
        } else {
            return destinationPointSets[0].featureCount();
        }
    }

}
