package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.models.RegionalAnalysis;

/**
 * Describes the status of a Job in a REST API response.
 */
public class JobStatus {

    /** The ID of this job. */
    public String jobId;

    /** The graph ID of this job. */
    public String graphId;

    /** The commit of R5 the worker should be running for this job. */
    public String workerCommit;

    /** The total number of tasks in this job. */
    public int total;

    /** The number of tasks that a worker has marked complete. */
    public int complete;

    /** The number of tasks no worker has yet marked complete. */
    public int incomplete;

    /** The total number of task deliveries that have occurred. Tasks will be counted more than once if redelivered. */
    public int deliveries;

    /** The number of times we have started over at the beginning to redeliver tasks never marked complete. */
    public int deliveryPass;

    /** Active instances working on this job. **/
    public int activeWorkers;

    /** The regional analysis associated with this job */
    public RegionalAnalysis regionalAnalysis;

    /** default constructor for JSON deserialization */
    public JobStatus () { /* do nothing */ }

    /** Summarize the given job to return its status over the REST API. */
    public JobStatus (Job job) {
        this.jobId = job.jobId;
        this.graphId = job.workerCategory.graphId;
        this.workerCommit = job.workerCategory.workerVersion;
        this.total = job.nTasksTotal;
        this.complete = job.nTasksCompleted;
        this.incomplete = total - complete;
        this.deliveries = job.nTasksDelivered;
        this.deliveryPass = job.deliveryPass;
    }
}
