package com.conveyal.r5.analyst.progress;

/**
 * This interface provides simple callbacks to allow long running, asynchronous operations to report on their progress.
 * Take care that all method implementations are very fast as the increment methods might be called in tight loops.
 */
public interface ProgressListener {

    /**
     * Call this method once at the beginning of a new task, specifying how many sub-units of work will be performed.
     * If totalElements is zero or negative, any previously set total number of elements remains unchanged.
     * This allows for subsequently starting named sub-tasks that use the same ProgressListener while progress is
     * still being reported. Any recursion launching sub-tasks will need to be head- or tail-recursion, launched before
     * you call beginTask or after the last unit of work is complete.
     * Rather than implementing some kind of push/pop mechanism, we may eventually have some kind of nested task system,
     * where all tasks are retrieved in a hierarchy and progress on sub-tasks bubbles up to super-tasks.
     * Or alternatively, we may never recurse into lazy-loading code, which is probably a better long term goal.
     */
    void beginTask(String description, int totalElements);

    /** Call this method to report that N units of work have been performed. */
    void increment(int n);

    /** Call this method to report that one unit of work has been performed. */
    default void increment () {
        increment(1);
    }

    /**
     * We want WorkProducts to be revealed by a TaskAction even in the case of exception or failure (to report complex
     * structured error or validation information). Returning them from the action method will not work in case of an
     * unexpected exception. Adding them to the background Task with a fluent method is also problematic as it requires
     * the caller to construct or otherwise hold a reference to the product to get its ID before the action is run. It's
     * preferable for the product to be fully encapsulated in the action, so it's reported as park of the task progress.
     * On the other hand, creating the product within the TaskAction usually requires it to hold a UserPermissions.
     */
    default void setWorkProduct (WorkProduct workProduct) { /* Default is no-op */ }

}
