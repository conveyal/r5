package com.conveyal.r5.analyst.progress;

/**
 * This interface provides simple callbacks to allow long running, asynchronous operations to report on their progress.
 */
public interface ProgressListener {

    /**
     * Call this method once at the beginning of a new task, specifying how many sub-units of work will be performed.
     * This does not allow for subsequently starting sub-tasks that use the same ProgressListener while progress is
     * still being reported. Any recursion launching sub-tasks will need to be head- or tail-recursion, launched before
     * you call beginTask or after the last unit of work is complete.
     * Rather than implementing some kind of push/pop mechanism, we may eventually have some kind of nested task system,
     * where all tasks are retrieved in a hierarchy and progress on sub-tasks bubbles up to super-tasks.
     * Or alternatively, we may never recurse into lazy-loading code, which is probably a better long term goal.
     */
    void beginTask(String description, int totalElements);

    /**
     * Call this method to report that one unit of work has been performed.
     */
    void increment();

}
