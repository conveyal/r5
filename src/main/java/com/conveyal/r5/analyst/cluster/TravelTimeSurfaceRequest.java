package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request a travel time surface containing travel times to all destinations for several percentiles of travel time.
 */
public class TravelTimeSurfaceRequest extends GenericClusterRequest {
    /** The profile request to use */
    public ProfileRequest request;

    public int zoom;
    public int west;
    public int north;
    public int width;
    public int height;

    /** Which percentiles to calculate */
    public double[] percentiles = new double[] { 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95 };

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
