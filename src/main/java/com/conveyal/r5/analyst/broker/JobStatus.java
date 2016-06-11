package com.conveyal.r5.analyst.broker;

/**
 * Describes the status of Job.
 */
public class JobStatus {

    /** The ID of this job. */
    public String jobId;

    /** The graph ID of this job. */
    public String graphId;

    /** The total number of tasks in this job. */
    public int total;

    /** The number of tasks that a worker has marked complete. */
    public int complete;

    /** The number of tasks no worker has yet marked complete. */
    public int incomplete;

    /** The number of tasks that are queued for delivery to a worker. */
    public int queued;

    /** The number of times the queue was emptied but incomplete tasks were re-added to it. */
    public int redeliveryCount;

    /** default constructor for JSON deserialization */
    public JobStatus () { /* nothing */ }

    /** Summarize the given job to return its status over the REST API. */
    public JobStatus (Job job) {
        this.jobId = job.jobId;
        this.graphId = job.graphId;
        this.total = job.tasksById.size();
        this.complete = job.completedTasks.size();
        this.incomplete = job.tasksById.size() - job.completedTasks.size();
        this.queued = job.tasksAwaitingDelivery.size();
        this.redeliveryCount = job.redeliveryCount;
    }

    /** Sum up the summmary info for a bunch of jobs. */
    public JobStatus (Iterable<JobStatus> statuses) {
        for (JobStatus status : statuses) {
            this.total += status.total;
            this.complete += status.complete;
            this.incomplete += status.incomplete;
            this.queued += status.queued;
            this.redeliveryCount += status.redeliveryCount;
        }
        this.jobId = "ALL";
        this.graphId = "ALL";
    }

}
