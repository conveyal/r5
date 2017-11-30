package com.conveyal.r5.analyst.broker;

import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A Job is a collection of tasks that represent all the origins in a regional analysis. All the tasks must have the
 * same network ID and be run against the same R5 version on the workers.
 *
 * There is no concern about multiple tasks having the same ID, because those IDs are created by the broker.
 * An unlikely potential problem is that if the broker restarts, the workers might mark the wrong tasks as completed.
 */
public class Job {

    private static final Logger LOG = LoggerFactory.getLogger(Job.class);

    public static final int REDELIVERY_WAIT_MSEC = 2 * 60 * 1000;

    public static final int MAX_REDELIVERIES = 2;

    // In order to provide realistic estimates of job processing time, we don't want to deliver the tasks to
    // workers in row-by-row geographic order, because spatial patterns exist in the world that make some areas
    // much faster than others. Ideally rather than storing the entire sequence (which is O(n) in the number of
    // tasks) you'd want to store a permutation seed that would allow instant lookup of the task in position N.
    // Essentially what we want to do is random selection (sampling) without replacement.
    // As far as I know there's no way to do this without storing the full sequence, or taking longer and longer
    // to find random tasks as the set of completed tasks gets larger.
    // On the other hand, working on tasks from the same geographic area might be more efficient because
    // they probably use all the same transit lines and roads, which will already be in cache.
    // So let's just keep track of where we're at in the sequence.

    private int nextTaskToDeliver;

    /* A unique identifier for this job, we use random UUIDs. */
    public final String jobId;

    // This can be derived from other fields but is provided as a convenience.
    public final int nTasksTotal;

//    // Each task will be checked off when it is delivered to a worker for processing.
//    // It may need to be redelivered if the worker shuts down or otherwise fails and never returns a result.
//    private final BitSet deliveredTasks;

    // Each task will be checked off when it a result is returned by the worker.
    // Once the worker has returned a result, the task will never be redelivered.
    private final BitSet completedTasks;

    // The number of remaining tasks can be derived from the deliveredTasks BitSet,
    // but as an optimization we keep a separate counter to avoid constantly scanning over that whole bitset.
    protected int nTasksCompleted;

    // Every task in this job will be based on this template task, but have its origin coordinates changed.
    private final RegionalTask templateTask;

    // This will serve as as a source of coordinates for each numbered task in the job - one per pointSet point.
    // We will eventually want to expand this to work with any PointSet of origins, not just a grid.
//    private final WebMercatorGridPointSet originGrid;

    /**
     * The only thing that changes from one task to the next is the origin coordinates.
     * @param taskNumber the task number within the job, equal to the point number within the origin point set.
     */
    private AnalysisTask makeOneTask (int taskNumber) {
        // Hack to deliver single-point requests, replace this eventually.
        if (tasks != null) return tasks.get(taskNumber);
        RegionalTask task = templateTask.clone();
        // We want to support any Pointset but for now we only have grids tied to the task itself.
        // In the future we'll set origin coords from a PointSet object.
        task.x = taskNumber % templateTask.width;
        task.y = taskNumber / templateTask.width;
        task.taskId = taskNumber++; //FIXME workers and broker expect this to be globally unique, not job-unique
        task.fromLat = Grid.pixelToCenterLat(task.north + task.y, task.zoom);
        task.fromLon = Grid.pixelToCenterLon(task.west + task.x, task.zoom);
        return task;
    }

    /**
     * The graph and r5 commit on which tasks are to be run.
     * All tasks contained in a job must run on the same graph and r5 commit.
     * TODO this field is kind of redundant - it's implied by the template request.
     */
    public final WorkerCategory workerCategory;

    // The last time tasks were delivered to a worker, in milliseconds since the epoch.
    // Enables a quiet period after all tasks have been delivered, before we attempt any re-delivery.
    long lastDeliveryTime = 0;

    // How many times we have started over delivering tasks, working through those that were not marked complete.
    public int deliveryPass = 0;

    public Job (RegionalTask templateTask) {
        this.jobId = templateTask.jobId;
        this.templateTask = templateTask;
        this.nTasksTotal = templateTask.width * templateTask.height;
        this.completedTasks = new BitSet(nTasksTotal);
        this.workerCategory = new WorkerCategory(templateTask.graphId, templateTask.workerVersion);
        this.nTasksCompleted = 0;
        this.nextTaskToDeliver = 0;
    }

    private List<AnalysisTask> tasks = null;

    /** create a special job of arbitrary tasks from the same worker category */
    public Job (List<AnalysisTask> tasks) {
        this.workerCategory = tasks.get(0).getWorkerCategory();
        for (AnalysisTask task : tasks) {
            if (!task.getWorkerCategory().equals(this.workerCategory)) {
                throw new RuntimeException("WORKER CATEGORY MISMATCH, FAIL.");
            }
        }
        this.jobId = "SINGLE_POINT_BATCH";
        this.templateTask = null;
        this.nTasksTotal = tasks.size();
        this.completedTasks = new BitSet(nTasksTotal);
        this.nTasksCompleted = 0;
        this.nextTaskToDeliver = 0;
        this.tasks = tasks;
    }

    public boolean markTaskCompleted(int taskId) {
        // Don't allow negative or huge task numbers to avoid exceptions or expanding the bitset to a huge size.
        if (taskId < 0 || taskId > nTasksTotal) {
            return false;
        }
        if (completedTasks.get(taskId)) {
            return false;
        } else {
            completedTasks.set(taskId);
            nTasksCompleted += 1;
            return true;
        }
    }

    /**
     * Check if there are any tasks that were delivered to workers but never marked as completed.
     * This could happen if the workers are spot instances and they are terminated by AWS while processing some tasks.
     * @return the number of tasks that have not been marked complete and will be redelivered.
     * FIXME we don't actually materialize a list of things to deliver so this method is no longer necessary except for logging purposes
     * FIXME this is getting called constantly, really we could just do it once in a while on a timer
     */
    public int redeliver () {

        // Only redeliver on jobs that are still active (that have some tasks not marked complete).
        if (this.isComplete()) return 0;

        // Only redeliver tasks once we've already delivered everything in this job (completed the current pass).
        if (nextTaskToDeliver < nTasksTotal) return 0;

        // If we've finished the last allowed redelivery attempt, don't do any more.
        if (this.deliveryPass >= MAX_REDELIVERIES) return 0;

        // After the last task is delivered, wait a while before redelivering to avoid spurious re-delivery.
        if (System.currentTimeMillis() - lastDeliveryTime < REDELIVERY_WAIT_MSEC) return 0;

        // If we arrive here, we have already delivered all tasks, but some of them are still not marked complete.
        // The quiet period has passed, so we start redelivering them.
        deliveryPass += 1;
        int nIncompleteTasks = nTasksTotal - nTasksCompleted;
        LOG.info("Beginning redelivery pass {} on job {} because {} delivered tasks are still incomplete.", deliveryPass, jobId, nIncompleteTasks);
        // Start back over at the beginning, it will only re-send tasks that are not marked as completed.
        nextTaskToDeliver = 0;
        return nIncompleteTasks;

    }

    public boolean isComplete() {
        return nTasksCompleted == nTasksTotal;
    }

    /**
     * @param maxTasks the maximum number of tasks to return.
     * @return some tasks that are not yet marked as completed and have not yet been delivered in this delivery pass.
     * This is returning AnalysisTasks instead of RegionalTasks because the first time a worker receives single-point
     * requests it's through this same mechanism.
     */
    public List<AnalysisTask> generateSomeTasksToDeliver (int maxTasks) {
        List<AnalysisTask> tasks = new ArrayList<>(maxTasks);
        // TODO use special bitset iteration syntax.
        while (nextTaskToDeliver < nTasksTotal && tasks.size() < maxTasks) {
            if (!completedTasks.get(nextTaskToDeliver)) {
                tasks.add(makeOneTask(nextTaskToDeliver));
            }
            nextTaskToDeliver += 1;
        }
        return tasks;
    }

    public boolean hasTasksToDeliver() {
        return nTasksCompleted < nTasksTotal && nextTaskToDeliver < nTasksTotal;
    }

    /**
     * Just as a failsafe, when our counter indicates that the job is complete, actually check how many bits are set.
     */
    public void verifyComplete() {
        if (this.isComplete() && completedTasks.cardinality() != nTasksTotal) {
            LOG.error("Something is amiss in completed task tracking.");
        }
    }

}
