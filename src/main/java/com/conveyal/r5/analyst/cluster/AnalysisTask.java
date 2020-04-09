package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.WebMercatorGridPointSetCache;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Describes an analysis task to be performed.
 * Instances are serialized and sent from the backend to workers when processing regional analyses.
 * By default, the task will be a travelTimeSurfaceTask for one origin.
 * This task is completed by returning a grid of total travel times from that origin to all destinations.
 */
@JsonTypeInfo(use= JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    // these match the enum values in AnalysisTask.Type
    @JsonSubTypes.Type(name = "TRAVEL_TIME_SURFACE", value = TravelTimeSurfaceTask.class),
    @JsonSubTypes.Type(name = "REGIONAL_ANALYSIS", value = RegionalTask.class)
})
public abstract class AnalysisTask extends ProfileRequest {

    /**
     * A loading cache of gridded pointSets (not opportunity data grids), shared statically among all single point and
     * multi-point (regional) requests. TODO make this non-static yet shared.
     */
    public static final WebMercatorGridPointSetCache gridPointSetCache = new WebMercatorGridPointSetCache();

    // Extents of a web Mercator grid. Unfortunately this grid serves different purposes in different requests.
    // In the single-origin TravelTimeSurfaceTasks, the grid points are the destinations.
    // In regional multi-origin tasks, the grid points are the origins, with destinations determined by the selected
    // opportunity dataset.
    // In regional Taui (static site) tasks the grid points serve as both the origins and the destinations.
    public int zoom;
    public int west;
    public int north;
    public int width;
    public int height;

    /** The ID of the graph against which to calculate this task. */
    public String graphId;

    /** The commit of r5 the worker should be running when it processes this task. */
    public String workerVersion;

    /** The job ID this is associated with. */
    public String jobId;

    /** The id of this particular origin. */
    public String originId;

    /** A unique identifier for this task assigned by the queue/broker system. */
    public int taskId;

    /**
     * Whether to save results on S3.
     * If false, the results will only be sent back to the broker or UI.
     * If true, travel time surfaces and paths will be saved to S3
     * Currently this only works on regional requests, and causes them to produce travel time surfaces instead of
     * accessibility indicator values.
     * FIXME in practice this currently implies computeTravelTimeBreakdown and computePaths, so we've got redundant and
     * potentially incoherent information in the request. The intent is in the future to make all these options
     * separate - we can make either travel time surfaces or accessibility indicators or both, and they may or may
     * not be saved to S3.
     */
    public boolean makeTauiSite = false;

    /** Whether to break travel time down into in-vehicle, wait, and access/egress time. */
    public boolean computeTravelTimeBreakdown = false;

    /** Whether to include paths in results. This allows rendering transitive-style schematic maps. */
    public boolean computePaths = false;

    /**
     * Which percentiles of travel time to calculate.
     * These should probably just be integers, but there are already a lot of them in Mongo as floats.
     */
    public int[] percentiles;

    /**
     * The travel time cutoffs in minutes for regional accessibility analysis.
     * A single cutoff was previously determined by superclass field ProfileRequest.maxTripDurationMinutes.
     * That field still cuts off the travel search at a certain number of minutes, so it is set to the highest cutoff.
     * Note this will only be set for accessibility calculation tasks, not for travel time surfaces.
     * TODO move it to the regional task subclass? Should this be called cutoffsMinutes as elsewhere?
     */
    public int[] cutoffsMinutes;

    /**
     * When recording paths as in a static site, how many distinct paths should be saved to each destination?
     * Currently this only makes sense in regional tasks, but it could also become relevant for travel time surfaces
     * so it's in this superclass.
     */
    public int nPathsPerTarget = 3;

    /**
     * Whether the R5 worker should log an analysis request it receives from the broker. analysis-backend translates
     * front-end requests to the format expected by R5. To debug this translation process, set logRequest = true in
     * the front-end profile request, then look for the full request received by the worker in its Cloudwatch log.
     */
    public boolean logRequest = false;

    /**
     * Is this a single point or regional request? Needed to encode types in JSON serialization. Can that type field be
     * added automatically with a serializer annotation instead of by defining a getter method and two dummy methods?
     */
    public abstract Type getType();

    /** Ensure that type is perceived as a field by serialization libs, no-op */
    public void setType (Type type) {};
    public void setTypes (String type) {};

    /**
     * @return extents for the appropriate destination grid, derived from task's bounds and zoom (for Taui site tasks
     * and single-point travel time surface tasks) or destination pointset (for standard regional accessibility
     * analysis tasks)
     */
    @JsonIgnore
    public abstract WebMercatorExtents getWebMercatorExtents();

    @JsonIgnore
    public WorkerCategory getWorkerCategory () {
        return new WorkerCategory(graphId, workerVersion);
    }

    public enum Type {
        /*
           TODO we should not need this enum - this should be handled automatically by JSON serializer annotations.
           These could also be changed to SINGLE_POINT and MULTI_POINT. The type of results requested (i.e. a grid of
           travel times per origin vs. an accessibility value per origin) can be inferred based on whether grids are
           specified in the profile request.  If travel time results are requested, flags can specify whether components
           of travel time (e.g. waiting) and paths should also be returned.
         */
        /** Binary grid of travel times from a single origin, for multiple percentiles, returned via broker by default */
        TRAVEL_TIME_SURFACE,
        /** Cumulative opportunity accessibility values for all cells in a grid, returned to broker for assembly*/
        REGIONAL_ANALYSIS
    }

    public AnalysisTask clone () {
        // no need to catch CloneNotSupportedException, it's caught in ProfileRequest::clone
        return (AnalysisTask) super.clone();
    }

    /**
     * @return the expected number of destination points for this particular kind of task.
     */
    public abstract int nTargetsPerOrigin();

}
