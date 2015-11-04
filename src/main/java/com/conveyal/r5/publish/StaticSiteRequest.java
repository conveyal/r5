package com.conveyal.r5.publish;

import com.conveyal.r5.profile.ProfileRequest;

/**
 * Configuration object for a static site, deserialized from JSON.
 */
public class StaticSiteRequest {
    /** Transport network ID to use */
    public String transportNetworkId;

    /** profile request */
    public ProfileRequest request;

    /** S3 bucket for result output */
    public String bucket;

    /** Prefix for result output */
    public String prefix;

    public PointRequest getPointRequest (int x, int y) {
        return new PointRequest(this, x, y);
    }

    /** Represents a single point of a static site request */
    public static class PointRequest {
        private PointRequest (StaticSiteRequest request, int x, int y) {
            this.request = request;
            this.x = x;
            this.y = y;
        }

        /** x pixel, relative to west side of transport network */
        public final int x;

        /** y pixel, relative to north edge of transport network */
        public final int y;

        /** StaticSiteRequest this is associated with */
        public final StaticSiteRequest request;
    }
}
