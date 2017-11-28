package com.conveyal.r5.analyst.broker;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A Job is a collection of tasks that represent all the origins in a regional analysis. All the tasks must have the
 * same network ID and be run against the same R5 version on the workers.
 *
 * There is no concern about multiple tasks having the same ID, because those IDs are created by the broker.
 * An unlikely potential problem is that if the broker restarts, the workers might mark the wrong tasks as completed.
 */
public class Job {

    private static final Logger LOG = LoggerFactory.getLogger(Job.class);

    public static final int REDELIVERY_QUIET_PERIOD_MSEC = 60 * 1000;

    /* A unique identifier for this job, usually a random UUID. */
    public final String jobId;

    /**
     * The graph and r5 commit on which tasks are to be run.
     * All tasks contained in a job must run on the same graph and r5 commit.
     */
    WorkerCategory workerCategory;

    /**
     * Tasks in this job that have yet to be delivered, or that will be re-delivered due to completion timeout.
     * Maybe this should only be a list of IDs.
     */
    Queue<AnalysisTask> tasksAwaitingDelivery = new ArrayDeque<>();

    /* The tasks in this job keyed on their task ID. */
    TIntObjectMap<AnalysisTask> tasksById = new TIntObjectHashMap<>();

    /* The last time tasks were delivered to a worker. Enables a quiet period between delivery and re-delivery. */
    long lastDeliveryTime = 0;

    /* The IDs of all tasks that have been marked completed. */
    TIntSet completedTasks = new TIntHashSet();

    /* How many times incomplete tasks were redelivered on this job. */
    public int redeliveryCount = 0;

    public Job (String jobId) {
        this.jobId = jobId;
    }

    /** Adds a task to this Job, assigning it a task ID number. */
    public void addTask (AnalysisTask task) {
        tasksById.put(task.taskId, task);
        tasksAwaitingDelivery.add(task);
    }

    /**
     * Check if there are any tasks that were delivered to workers but never marked as completed.
     * This could happen if the workers are spot instances and they are terminated by AWS while processing some tasks.
     */
    public int redeliver () {

        // Only redeliver on jobs that are still active (that have some tasks not marked complete).
        if (this.isComplete()) return 0;

        // Only redeliver tasks when the delivery queue is empty.
        // This reduces the frequency of redelivery operations and is more predictable.
        if (tasksAwaitingDelivery.size() > 0) return 0;

        // After the last task is delivered, wait a while before redelivering to avoid spurious re-delivery.
        if (System.currentTimeMillis() - lastDeliveryTime < REDELIVERY_QUIET_PERIOD_MSEC) return 0;

        // If we arrive here, we should have an empty delivery queue but some tasks that are not marked complete.
        tasksById.forEachEntry((taskId, task) -> {
            if (!completedTasks.contains(taskId)) {
                tasksAwaitingDelivery.add(task);
            }
            return true;
        });
        redeliveryCount += 1;
        LOG.info("Re-enqueued {} incomplete tasks for delivery on job {}.", tasksAwaitingDelivery.size(), this.jobId);
        return tasksAwaitingDelivery.size();

    }

    public boolean isComplete() {
        return completedTasks.size() == tasksById.size();
    }

    public boolean containsTask (int taskId) {
        AnalysisTask req = tasksById.get(taskId);
        if (req != null) {
            if (!req.jobId.equals(this.jobId)) {
                LOG.error("Task {} has a job ID that does not match the job in which it was discovered.");
            }
            return true;
        }
        return false;
    }

    public WorkerCategory getWorkerCategory() {
        return workerCategory;
    }

}
