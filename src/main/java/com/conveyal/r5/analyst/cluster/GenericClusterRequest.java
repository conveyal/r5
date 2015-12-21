package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.publish.StaticSiteRequest;
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
        @JsonSubTypes.Type(name = "analyst", value = AnalystClusterRequest.class)
})
public abstract class GenericClusterRequest implements Serializable {
    /** The ID of the graph against which to calculate this request */
    public String graphId;

    /** The job ID this is associated with */
    public String jobId;

    /** The id of this particular origin */
    public String id;

    /** A unique identifier for this request assigned by the queue/broker system. */
    public int taskId;
}
