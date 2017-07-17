package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSetCache;
import com.conveyal.r5.analyst.broker.WorkerCategory;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.OutputStream;

/**
 * Describe an analysis task to be performed, which could be a single point interactive task for a surface of travel times,
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
    protected static WebMercatorGridPointSetCache gridPointSetCache = new WebMercatorGridPointSetCache();

    /** Whether this task is high priority and should jump in front of other work. */
    @JsonIgnore
    public abstract boolean isHighPriority();

    /** Get the destinations that should be used for this task */
    @JsonIgnore
    public abstract PointSet getDestinations(TransportNetwork network, GridCache gridCache);

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
        /** Binary grid of travel time for multiple percentiles returned via broker */
        TRAVEL_TIME_SURFACE,
        /** Bootstrapped accessibility results returned over SQS. */
        REGIONAL_ANALYSIS
    }

    public AnalysisTask clone () {
        // no need to catch CloneNotSupportedException, it's caught in ProfileRequest::clone
        return (AnalysisTask) super.clone();
    }
}
