package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSetCache;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Describes an analysis task to be performed.
 *
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
    public String id;

    /** A unique identifier for this task assigned by the queue/broker system. */
    public int taskId;

    /**
     * Whether to save results on S3.
     * If false, the results will only be sent back to the broker or UI.
     * If true, travel time surfaces will be saved to S3
     * Currently this only works on regional requests, and causes them to produce travel time surfaces instead of
     * accessibility indicator values.
     * FIXME in practice this always implies travelTimeBreakdown and returnPaths, so we've got redundant and potentially incoherent information in the request.
     * The intent is in the future to make all these options separate - we can make either travel time surfaces or
     * accessibility indicators or both, and they may or may not be saved to S3.
     */
    public boolean makeStaticSite = false;

    /** Whether to break travel time down into in-vehicle, wait, and access/egress time. */
    public boolean travelTimeBreakdown = false;

    /** Whether to include paths in results. This allows rendering transitive-style schematic maps. */
    public boolean returnPaths = false;

    /** Which percentiles of travel time to calculate. */
    public double[] percentiles = new double[] { 50 };

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
     * Is this a task that should return a binary travel time surface or compute accessibility and return it via SQS
     * to be saved in a regional analysis grid file?
     */
    public abstract Type getType();

    /** Ensure that type is perceived as a field by serialization libs, no-op */
    public void setType (Type type) {};
    public void setTypes (String type) {};

    /**
     * Whether this task is high priority and should jump in front of other work.
     * TODO eliminate and use polymorphism, this is only used in one place.
     */
    @JsonIgnore
    public abstract boolean isHighPriority();

    @JsonIgnore
    public WorkerCategory getWorkerCategory () {
        return new WorkerCategory(graphId, workerVersion);
    }

    public enum Type {
        /* TODO these could be changed, to SINGLE_POINT and MULTI_POINT. The type of results requested (i.e. a grid of
           travel times per origin vs. an accessibility value per origin) can be inferred based on whether grids are
           specified in the profile request.  If travel time results are requested, flags can specify whether components
           of travel time (e.g. waiting) and paths should also be returned.
         */
        /** Binary grid of travel times from a single origin, for multiple percentiles, returned via broker by default */
        TRAVEL_TIME_SURFACE,
        /** Bootstrapped accessibility results for multiple origins, returned over SQS by default. */
        REGIONAL_ANALYSIS
    }

    public AnalysisTask clone () {
        // no need to catch CloneNotSupportedException, it's caught in ProfileRequest::clone
        return (AnalysisTask) super.clone();
    }

}
