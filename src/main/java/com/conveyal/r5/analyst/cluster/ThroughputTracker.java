package com.conveyal.r5.analyst.cluster;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Keep track of tasks throughput on worker, grouped by job.
 * All public methods should be synchronized since they will be called by several different threads at once.
 */
public class ThroughputTracker {

    /**
     * A list of times at which tasks have been completed. Regularly truncated to only times in the last minute.
     * This allows reporting average throughput over different timescales up to one minute.
     * TODO replace Long times with TaskStats containing more info about compute time breakdown
     */
    private Map<String, List<Long>> recentTaskCompletionTimesByJob = new HashMap<>();

    /**
     * Indicate to the tracker that a task has just been completed for the specified job.
     */
    public synchronized void recordTaskCompletion(String jobId) {
        List<Long> times = recentTaskCompletionTimesByJob.get(jobId);
        if (times == null) {
            // Use a linked list so that elsewhere we can efficiently repeatedly remove the zeroth element.
            times = new LinkedList<>();
            recentTaskCompletionTimesByJob.put(jobId, times);
        }
        times.add(System.currentTimeMillis());
    }

    /**
     * @return the number of tasks completed in the last minute, broken down by job. Intended to be serialized as JSON.
     */
    public synchronized Map<String, Integer> getTasksPerMinuteByJobId () {
        // First clear out all tasks more than one minute old for every job.
        removeOldTasks();
        // Then summarize how many tasks were recorded in the last minute for each job.
        Map<String, Integer> tasksPerMinuteByJobId = new HashMap<>();
        recentTaskCompletionTimesByJob.forEach((jobId, times) ->
                tasksPerMinuteByJobId.put(jobId, times.size()));
        return tasksPerMinuteByJobId;
    }

    /**
     * Remove all tasks from the per-job lists that are more than 1 minute old.
     * Any jobs for which no tasks have been finished in the last minute will be removed from the map.
     */
    private synchronized void removeOldTasks () {
        long oneMinuteAgo = System.currentTimeMillis() - 1000 * 60;
        Iterator<Map.Entry<String, List<Long>>> iterator = recentTaskCompletionTimesByJob.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<Long>> entry = iterator.next();
            List<Long> times = entry.getValue();
            while (!times.isEmpty() && times.get(0) < oneMinuteAgo) {
                // For efficiency this should be operating on a linked list rather than an array.
                times.remove(0);
            }
            if (times.isEmpty()) iterator.remove();
        }
    }

}
