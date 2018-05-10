package com.conveyal.r5.analyst;

import java.io.Serializable;
import java.util.Objects;

/**
 * This identifies a category of workers that are all running the same R5 commit and have the same graph loaded.
 * TODO perhaps this is a TaskCategory rather than a WorkerCategory.
 */
public class WorkerCategory implements Comparable<WorkerCategory>, Serializable {

    public final String graphId;
    public final String workerVersion;

    public WorkerCategory(String graphId, String workerCommit) {
        this.graphId = graphId;
        this.workerVersion = workerCommit;
    }

    @Override
    public String toString() {
        return "graph ID '" + graphId + '\'' + ", worker version '" + workerVersion + '\'';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkerCategory that = (WorkerCategory) o;
        return Objects.equals(graphId, that.graphId) &&
                Objects.equals(workerVersion, that.workerVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(graphId, workerVersion);
    }

    @Override
    public int compareTo (WorkerCategory other) {
        int result = this.graphId.compareTo(other.graphId);
        return result == 0 ? this.workerVersion.compareTo(other.workerVersion) : result;
    }

}
