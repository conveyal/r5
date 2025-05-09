package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorExtents;

/**
 * Represents a task to be performed as part of a regional analysis.
 * Instances are serialized and sent from the backend to workers when processing regional analyses.
 */
public class RegionalTask extends AnalysisWorkerTask implements Cloneable {

    /**
     * Key for pointset (e.g. regionId/datasetId.pointset) from which to calculate travel times or accessibility
     */
    public String originPointSetKey;

    /**
     * The PointSet instance looked up from the originPointSetKey.
     * Transient so it's not serialized when the task is sent over the wire, but still available wherever we need to
     * look up lat/lon coordinates or the number of origin points etc.
     */
    public transient PointSet originPointSet;

    /**
     * Whether to calculate travel time from each origin to one corresponding destination (the destination at the
     * same position in the destionationPointSet). If false, travel time calculations will be many-to-many (between
     * all origin points and all destination points).
     */
    public boolean oneToOne = false;

    /**
     * Whether to record travel times between origins and destinations. This is done automatically for
     * TravelTimeSurfaceTask (single point tasks) but must be manually enabled on RegionalTasks using this field.
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
     * road network is smaller than the opportunity data, but that should be rare. We could intersect with the extents
     * of the street network, but that probably requires access to the loaded TransportNetwork.
     */
    @Override
    public WebMercatorExtents getWebMercatorExtents() {
        if (makeTauiSite || this.hasFlag("CROP_DESTINATIONS")) {
            return WebMercatorExtents.forTask(this);
        } else {
            return WebMercatorExtents.forPointsets(this.destinationPointSets);
        }
    }

    /**
     * WARNING This whole tree of classes contains non-primitive compound fields. Cloning WILL NOT DEEP COPY these
     * fields. Modifying some aspects of the cloned object may modify the same aspects of the one it was cloned from.
     * Unfortunately these classes have a large number of fields and maintaining hand written copy constructors for
     * them might be an even greater liability than carefully choosing how to use clone().
     */
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

    public boolean hasFlag (String flag) {
        return this.flags != null && this.flags.contains(flag);
    }

}
