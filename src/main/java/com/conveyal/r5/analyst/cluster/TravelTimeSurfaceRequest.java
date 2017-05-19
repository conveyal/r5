package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request a travel time surface containing travel times to all destinations for all iterations.
 */
public class TravelTimeSurfaceRequest extends GenericClusterRequest {
    /** The profile request to use */
    public ProfileRequest request;

    public int zoom;
    public int west;
    public int north;
    public int width;
    public int height;

    /** The grid key on S3 to compute access to */
    public String grid;

    public final String type = "travel-time-surface";

    @Override
    public ProfileRequest extractProfileRequest() {
        return request;
    }

    @Override
    public boolean isHighPriority() {
        return true; // travel time surfaces used in high priority requests only
    }
}
