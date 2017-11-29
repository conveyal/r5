package com.conveyal.r5.multipoint;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.profile.ProfileRequest;

import java.util.UUID;

/**
 * Configuration object for a static site, deserialized from JSON.
 */
public class MultipointRequest {
    /** Transport network ID to use */
    public String transportNetworkId;

    /** Worker version */
    public String workerVersion;

    /** profile request */
    public ProfileRequest request;

    /** S3 bucket for result output */
    public String bucket;

    /** Prefix for result output */
    public String prefix;

    public final String jobId = UUID.randomUUID().toString().replace("-", "");

    public PointRequest getPointRequest (int x, int y) {
        return new PointRequest(this, x, y);
    }

    /** Represents a single point of a static site request */
    public static class PointRequest extends AnalysisTask {
        public final String type = "static";

        private PointRequest (StaticSiteRequest request, int x, int y) {
            this.request = request;
            this.x = x;
            this.y = y;
            this.workerVersion = request.workerVersion;
            this.graphId = request.transportNetworkId;
            this.jobId = request.jobId;
            this.id = x + "_" + y;
        }

        /** no-arg constructor for deserialization */
        public PointRequest () { /* do nothing */ }

        /** x pixel, relative to west side of transport network */
        public int x;

        /** y pixel, relative to north edge of transport network */
        public int y;

        /** StaticSiteRequest this is associated with */
        public StaticSiteRequest request;

        @Override
        public ProfileRequest extractProfileRequest() {
            return request.request;
        }

        @Override
        public boolean isHighPriority() {
            return request.bucket == null; // null bucket means will be returned directly over HTTP
        }

    }
}