package com.conveyal.r5.analyst.progress;

import com.google.common.collect.Lists;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This could replace our current bare ExecutorServices class. This can remain all-static for simplicity at first.
 * Like many other things in Analysis it should probably become a singleton instance (though this is a pure question of
 * style for the moment, as we never have more than one instance running in the same JVM).
 *
 * This executor is in R5 rather than analysis-backend, so does not now have access to AnalysisServerConfig.lightThreads
 * and AnalysisServerConfig.heavyThreads config options. Pool sizes would need to be initialized manually at startup.
 * There might not be any reason for it to be in R5 if it's entirely managing backend tasks. But we do expect to at
 * least manage and report on progress building networks and distance tables, which needs to happen in a specific
 * versioned worker instance.
 *
 * This is moving in the direction of having a single unified task management and reporting system across the backend
 * and workers. It could be interesting to gather task status from the whole cluster of workers and merge them together
 * into one view. This could conceivably even include regional analyses and chunks of work for regional analyses on
 * workers. But even if such merging doesn't occur, it will be convenient to always report progress from backend and
 * workers to the UI in the same data structures.
 */
public abstract class TaskExecutor {

    private static final ExecutorService light = Executors.newFixedThreadPool(10);
    private static final ExecutorService heavy = Executors.newFixedThreadPool(2);

    public static void enqueue (Task task) {
        task.validate();
        if (task.isHeavy()) {
            heavy.submit(task);
        } else {
            light.submit(task);
        }
    }

    /**
     * Just demonstrating how this would be used.
     */
    public static void example () {
        TaskExecutor.enqueue(Task.newTask()
                .forUser("abyrd@conveyal.com", "conveyal")
                .withDescription("Process some complicated things")
                .withTotalWorkUnits(1024)
                .withAction((progressListener -> {
                    double sum = 0;
                    for (int i = 0; i < 1024; i++) {
                        sum += Math.sqrt(i);
                        progressListener.increment();
                    }
                }))
        );
    }

    /**
     * @return a hierarchical structure of all currently executing tasks, for serialization and transmission to the UI.
     */
    public static List<Task> getAllTasksForUI () {
        // Hmm, we need to get all the tasks back out of the Executor once they're seen as Runnables...
        return Lists.newArrayList();
    }

}
