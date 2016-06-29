package com.conveyal.r5.integration;

import com.conveyal.r5.profile.ProfileRequest;

import java.util.List;

/**
 * Request diagnostics for a particular location.
 */
public class DiagnosticsRequest {
    /** how many samples should we take? */
    public int samples = 1000;

    /** what is the ID of this request? */
    public String id;

    /** What is the ID of this particular test run (encompassing multiple requests) */
    public String testId;

    public String networkBucket;

    public String pointsetBucket;

    public String networkId;

    public String pointsetId;

    public String pointsetField;

    public ProfileRequest request;

    public String outputBucket;

    public List<int[]> results; // left null on request but used when saving results

    public boolean spectrograph;
}
