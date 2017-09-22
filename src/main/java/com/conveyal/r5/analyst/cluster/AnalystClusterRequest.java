package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

/**
 * A class for vanilla Analyst requests with a pointset and a transportnetwork.
 */
public class AnalystClusterRequest extends GenericClusterRequest implements Serializable {

    public static final long serialVersionUID = 1L;

    public final String type = "analyst";

    /** The ID of the destinations pointset */
    public String destinationPointsetId;

    /** The Analyst Cluster user that created this request */
    public String userId;

    /** To where should the result be POSTed */
    @Deprecated
    public String directOutputUrl;

    /**
     * Where should the job be saved?
     */
    public String outputLocation;

    /**
     * The routing parameters to use for a one-to-many profile request.
     * Non-profile one-to-many requests are represented by simply setting the time window to zero width, i.e.
     * in profileRequest fromTime == toTime.
     * Non-transit one-to-many requests are represented by setting profileRequest.transitModes to null or empty.
     * In that case only the directModes will be used to reach the destinations on the street.
     */
    public ProfileRequest profileRequest;

    /** Should times be included in the results (i.e. ResultSetWithTimes rather than ResultSet) */
    public boolean includeTimes = false;

    private AnalystClusterRequest (String destinationPointsetId, String graphId) {
        this.destinationPointsetId = destinationPointsetId;
        this.graphId = graphId;
    }

    /**
     * We're now using ProfileRequests for everything (no RoutingRequests for non-transit searches).
     * An GenericClusterRequest is a wrapper around a ProfileRequest with some additional settings and context.
     */
    public AnalystClusterRequest (String destinationPointsetId, String graphId, ProfileRequest req) {
        this(destinationPointsetId, graphId);
        profileRequest = req.clone();

        profileRequest.toLat = profileRequest.fromLat;
        profileRequest.toLon = profileRequest.fromLon;
    }

    /** Used for deserialization from JSON */
    public AnalystClusterRequest () { /* do nothing */ }

    @Override
    // Not named like a getter to avoid confusing the JSON serializer. This should not be serialized.
    public ProfileRequest extractProfileRequest() {
        return profileRequest;
    }

    @Override
    public boolean isHighPriority() {
        return outputLocation == null; // return results directly to client over HTTP
    }

}
