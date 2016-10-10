package com.conveyal.r5.analyst.broker;

/**
 *
 */
public class WorkerObservation {

    public final String workerId;
    public final WorkerCategory category;
    public final long lastSeen;

    public WorkerObservation (String workerId, WorkerCategory category) {
        this.workerId = workerId;
        this.category = category;
        this.lastSeen = System.currentTimeMillis();
    }

    /** This method is here to enrich the REST API responses, making them more human readable. */
    public long getSecondsAgo() {
        return (System.currentTimeMillis() - lastSeen) / 1000;
    }

}
