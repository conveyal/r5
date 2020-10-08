package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.models.Bundle;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * Updated each time the worker contacts the broker.
 * This class is used by the broker to keep track of the pool of workers contacting it, but also used to serialize
 * worker information in the HTTP API so the web client can visualize the worker pool.
 */
public class WorkerObservation {

    public final String workerId;

    @JsonIgnore
    public final WorkerCategory category;

    /** The last time this worker contacted the broker, in milliseconds since the epoch. */
    @JsonIgnore
    public final long lastSeen;

    @JsonUnwrapped // The workerStatus fields will be serialized as if they were part of this root object
    public final WorkerStatus status;

    public List<Bundle> bundles;

    // Eventually observation should probably be merged with the status it contains.
    public WorkerObservation (WorkerStatus status) {
        this.workerId = status.workerId;
        this.category = status.getWorkerCategory();
        this.lastSeen = System.currentTimeMillis();
        this.status = status;
    }

    /** This method is here to enrich the REST API responses, making them more human readable. */
    @JsonInclude
    public long getSeenSecondsAgo() {
        return (System.currentTimeMillis() - lastSeen) / 1000;
    }
}
