package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.broker.WorkerCategory;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.OutputStream;
import java.util.List;

/**
 * Describe an analysis task to be performed, which could be a single point interactive task for
 * a surface of travel times (default),
 * or could be a regional task for bootstrapped accessibility values.
 */
@JsonTypeInfo(use= JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    // these match the enum values in AnalysisTask.Type
    @JsonSubTypes.Type(name = "TRAVEL_TIME_SURFACE", value = TravelTimeSurfaceTask.class),
    @JsonSubTypes.Type(name = "REGIONAL_ANALYSIS", value = RegionalTask.class)
})
public abstract class AnalysisTask extends ProfileRequest {
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

    /** Save results to a bucket.  If blank, the response to this task will be via the default channel (broker for
     *  single-point requests, queue for regional requests).  Not yet compatible with UI.
     */
    @JsonIgnore
    public String outputBucket = "";

    /* Directory in the bucket in which to save results; not yet compatible with UI */
    @JsonIgnore
    public String outputDirectory = "";

    /** Whether to include the in-vehicle component of overall travel time in results */
    @JsonIgnore
    public boolean returnInVehicleTimes = false;

    /** Whether to include the waiting time component of overall travel time in results */
    @JsonIgnore
    public boolean returnWaitTimes = false;

    /** Whether to include paths, used to for transitive-style maps, in results */
    @JsonIgnore
    public boolean returnPaths = false;

    /** Which percentiles of travel time to calculate. */
    public double[] percentiles = new double[] { 50 };

    /**
     * Is this a task that should return a binary travel time surface or compute accessibility and return it via SQS
     * to be saved in a regional analysis grid file?
     */
    public abstract Type getType();

    /** Ensure that type is perceived as a field by serialization libs, no-op */
    public void setType (Type type) {};
    public void setTypes (String type) {};

    /** Share this among both single and multipoint requests */
    protected static PointSetCache pointSetCache = new PointSetCache();

    /** Whether this task is high priority and should jump in front of other work. */
    @JsonIgnore
    public abstract boolean isHighPriority();

    /**
     * Get the destinations that should be used for this task. These destinations are submitted
     * and linked in the build step for lower latency.
     * We could add the ability to set a different list of destinations on every request, but
     * the required insertIfAbsent into cache on each request adds enough latency that we have
     * chosen to leave it unimplemented until that functionality is needed.
     */
    @JsonIgnore
    public abstract List<PointSet> getDestinations(TransportNetwork network, GridCache gridCache);

    /**
     * Get a TravelTimeReducer that will finish this task.
     *
     * The computation of travel times is the same for all task types, but how they are summarized (a surface of travel
     * times, bootstrapped accessibility, etc) is not.
     */
    @JsonIgnore
    public abstract PerTargetPropagater.TravelTimeReducer getTravelTimeReducer(TransportNetwork network, OutputStream os);

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
        /** Binary grid of travel times from a single origin. */
        TRAVEL_TIME_SURFACE,
        /** Bootstrapped accessibility results for multiple origins, returned over SQS by default. */
        REGIONAL_ANALYSIS
    }

    public AnalysisTask clone () {
        // no need to catch CloneNotSupportedException, it's caught in ProfileRequest::clone
        return (AnalysisTask) super.clone();
    }
}
