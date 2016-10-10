package com.conveyal.r5.analyst.broker;

/**
 * Object to report back the status of the cluster.
 */
public class ClusterStatus {
    public Status status;

    public ClusterStatus (Status status) {
        this.status = status;
    }

    public enum Status {
        CLUSTER_STARTING_UP, GRAPH_BUILDING
    }
}
