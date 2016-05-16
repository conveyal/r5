package com.conveyal.r5.analyst.cluster.lambda;

import com.conveyal.r5.profile.ProfileRequest;

/**
 * An extended ProfileRequest with fields to specify where data should come from and where and in what format results
 * should be returned.
 */
public class LambdaClusterRequest extends ProfileRequest {
    /** ID of transport network to use */
    public String networkId;

    /** S3 bucket in which to find said transport network */
    public String networkBucket;

    /** Result type, e.g. GeoJSON isochrones, TAUI files, EBCDIC-encoded plain text . . . */
    public ResultType resultType;

    /** S3 bucket in which to save results, if null results will be streamed back to the client in raw form. */
    public String resultBucket = null;

    /** ID of this request, used when saving files into the result bucket */
    public String requestId;

    /** ID of this job, also used when saving files into the result bucket */
    public String jobId;

    /** Types of results that can be returned */
    public static enum ResultType {
        /** GeoJSON isochrones, one for each time cutoff from 0 to 120 minutes at 5-minute intervals */
        GEOJSON_ISOCHRONES,

        /** TAUI format origin files */
        TAUI
    }
}
