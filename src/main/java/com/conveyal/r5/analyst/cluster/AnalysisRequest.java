package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.broker.WorkerCategory;
import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Request a travel time surface containing travel times to all destinations for several percentiles of travel time.
 */
public class AnalysisRequest extends ProfileRequest {
    public int zoom;
    public int west;
    public int north;
    public int width;
    public int height;

    /**
     * If specified and nonnegative, will be used as coordinates into grid above and will override
     * fromLat and fromLon.
     */
    public int x = -1, y = -1;

    /** The ID of the graph against which to calculate this request. */
    public String graphId;

    /** The commit of r5 the worker should be running when it processes this request. */
    public String workerVersion;

    /** The job ID this is associated with. */
    public String jobId;

    /** The id of this particular origin. */
    public String id;

    /** A unique identifier for this request assigned by the queue/broker system. */
    public int taskId;

    /** Which percentiles to calculate. */
    public double[] percentiles = new double[] { 50 };

    /** The grid key on S3 to compute access to, if this request is part of a regional analysis? */
    public String grid;

    /** Where should output of this job be saved, if this request is part of a regional analysis? */
    public String outputQueue;

    /**
     * Is this a request that should return a binary travel time surface or compute accessibility and return it via SQS
     * to be saved in a regional analysis grid file?
     */
    public Type type;

    /** Travel time surfaces (single point requests) are high priority. Regional analyses are not */
    @JsonIgnore
    public boolean isHighPriority() {
        return type == Type.TRAVEL_TIME_SURFACE;
    }

    @JsonIgnore
    public WorkerCategory getWorkerCategory () {
        return new WorkerCategory(graphId, workerVersion);
    }

    public enum Type {
        /** Binary grid of travel time for multiple percentiles returned via broker? */
        TRAVEL_TIME_SURFACE,
        /** Bootstrapped accessibility results returned over SQS. */
        REGIONAL_ANALYSIS
    }

    public AnalysisRequest clone () {
        // no need to catch CloneNotSupportedException, it's caught in ProfileRequest::clone
        return (AnalysisRequest) super.clone();
    }
}
