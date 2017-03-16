package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.broker.WorkerCategory;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.publish.StaticMetadata;
import com.conveyal.r5.publish.StaticSiteRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * A request sent to an Analyst cluster worker.
 * It has two separate fields for RoutingReqeust or ProfileReqeust to facilitate binding from JSON.
 * Only one of them should be set in a given instance, with the ProfileRequest taking precedence if both are set.
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include= JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "static", value = StaticSiteRequest.PointRequest.class),
        @JsonSubTypes.Type(name = "analyst", value = AnalystClusterRequest.class),
        @JsonSubTypes.Type(name = "grid", value = GridRequest.class),
        @JsonSubTypes.Type(name = "static-metadata", value = StaticMetadata.MetadataRequest.class),
        @JsonSubTypes.Type(name = "static-stop-trees", value = StaticMetadata.StopTreeRequest.class)
})
public abstract class GenericClusterRequest implements Serializable {

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

    @JsonIgnore
    public WorkerCategory getWorkerCategory() {
        return new WorkerCategory(graphId, workerVersion);
    }

    /**
     * Should be overridden by subclasses to return the ProfileRequest nested within a particular type of cluster
     * request.
     * TODO Since all subtypes have a ProfileRequest, maybe there should just be a consistent field profileRequest in this superclass.
     * Annoyingly this will get serialized if it's named like a "getter" method (named getX) and even adding an @JsonIgnore
     * annotation does not help, perhaps you'd need to add the annotation to every single subclass.
     */
    public abstract ProfileRequest extractProfileRequest();

    /**
     * Should be overridden by subclasses to determine whether they are high priority or not.
     */
    @JsonIgnore
    public abstract boolean isHighPriority();

}