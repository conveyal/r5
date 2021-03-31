package com.conveyal.analysis.components;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.r5.analyst.progress.Task;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * This provides application-wide queues of one-off or repeating tasks. It ensures that some tasks repeat regularly
 * while others are completed without too many tasks being handled concurrently. The two separate executors provide a
 * "heavy" executor (for slow tasks) and a "light" executor (for fast tasks, the "passing lane"), each with a number
 * of threads that can be limited in the main analysis.properties configuration file to help limit heavy concurrent
 * operations.
 *
 * The heavy/light distinction is somewhat like a work-stealing pool (see Javadoc on Executors.newWorkStealingPool()).
 * However with the workStealingPool we have no guarantees it will create more than one queue, or on execution order.
 * The implementation with ExecutorServices also seems to allow more control over tasks causing exceptions.
 *
 * ===
 *
 * This also serves to report active tasks for a given user.
 * That could be considered a separate function, but it ended up having a bidirectional dependency on this
 * TaskScheduler so they've been merged.
 *
 * ===
 *
 * This is moving in the direction of having a single unified task management and reporting system across the backend
 * and workers. It could be interesting to gather task status from the whole cluster of workers and merge them together
 * into one view. This could conceivably even include regional analyses and chunks of work for regional analyses on
 * workers. But even if such merging doesn't occur, it will be convenient to always report progress from backend and
 * workers to the UI in the same data structures.
 *
 * So we're moving toward a programming API where you submit Tasks with attached Actions.
 * TODO state that all Components should be threadsafe, i.e. should not fail if used by multiple HTTP handler threads.
 *
 * Eventually every asynchronous task should be handled by this one mechanism, to ensure every Throwable is caught and
 * cannot kill threads, as well as standardized reporting and tracking of backend and worker activity.
 */
public class TaskScheduler implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);

    // The Javadoc on the ExecutorService interface implies that it's threadsafe by mentioning happens-before.
    // Inspecting the source code of ThreadPoolExecutor we see it uses locks to make task submission threadsafe.
    // So we don't need to explicitly synchronize use of these executor services from multiple simultaneous requests.
    private final ScheduledExecutorService scheduledExecutor;
    private final ExecutorService lightExecutor;
    private final ExecutorService heavyExecutor;

    // Keep the futures returned when tasks are scheduled, which give access to status information and exceptions.
    // This should facilitate debugging. We may want to do the same thing for one-off tasks.
    // Note this will need to be synchronized if we ever allow canceling periodic tasks.
    private final List<ScheduledFuture> periodicTaskFutures = new ArrayList<>();

    // Keep track of tasks submitted by each user, for reporting on their progress over the HTTP API.
    // Synchronized because multiple users may add things to this map from multiple HTTP server threads.
    private final SetMultimap<String, Task> tasks = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    public interface Config {
        int lightThreads ();
        int heavyThreads ();
    }

    /** Interface for all actions that we want to repeat at regular intervals. */
    // TODO this could be merged with all other tasks - getPeriodSeconds returns -1 for non-periodic.
    //      However there are advantages to single method interfaces.
    //      Heavy/light/periodic should be indicated on the Task rather than the TaskAction passed in.
    //      ProgressListener needs methods to markComplete() and reportError(Throwable)
    public interface PeriodicTask extends Runnable {
        int getPeriodSeconds();
    }

    public TaskScheduler (Config config) {
        scheduledExecutor = Executors.newScheduledThreadPool(1);
        lightExecutor = Executors.newFixedThreadPool(config.lightThreads());
        heavyExecutor = Executors.newFixedThreadPool(config.heavyThreads());
    }

    /** TODO handle worker record scavenging and cluster stats reporting with this. */
    // Require an interface extending runnable to pass something to the TaskScheduler constructor?
    // Perhaps start periodic tasks automatically on TaskScheduler construction.
    public void repeatRegularly (PeriodicTask periodicTask) {
        String className = periodicTask.getClass().getSimpleName();
        int periodSeconds = periodicTask.getPeriodSeconds();
        LOG.info("An instance of {} will run every {} seconds.", className, periodSeconds);
        ErrorTrap wrappedPeriodicTask = new ErrorTrap(periodicTask);
        periodicTaskFutures.add(
            scheduledExecutor.scheduleAtFixedRate(wrappedPeriodicTask, periodSeconds, periodSeconds, TimeUnit.SECONDS)
        );
    }

    public void enqueueLightTask (Runnable runnable) {
        lightExecutor.submit(new ErrorTrap(runnable));
    }

    public void enqueueHeavyTask (Runnable runnable) {
        heavyExecutor.submit(new ErrorTrap(runnable));
    }

    /**
     * Wrap a runnable, catching any Errors or Exceptions that occur. This prevents them from propagating up to the
     * scheduled executor, which would swallow them and silently halt the periodic execution of the runnable.
     */
    private static class ErrorTrap implements Runnable {

        private final Runnable runnable;

        public ErrorTrap (Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public final void run () {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOG.error("Background execution of {} caused exception: {}", runnable.getClass(), t.toString());
                t.printStackTrace();
            }
        }
    }

    public Task newTaskForUser (UserPermissions user) {
        Task task = new Task()
                .withTag("accessGroup", user.accessGroup)
                .withTag("email", user.email);
        tasks.put(user.email, task);
        return task;
    }

    /** Return an empty list even when no tasks have been recorded for the user (return is always non-null). */
    public List<Task> getTasksForUser(UserPermissions user) {
        Set<Task> tasks = this.tasks.get(user.email);
        return tasks == null ? Collections.emptyList() : List.copyOf(tasks);
    }

    // TODO save cleared tasks to MongoDB for analyzing later?
    public void clearAllCompletedForUser(UserPermissions user) {
        synchronized (tasks) {
            tasks.get(user.email).removeIf(task -> task.state == Task.State.DONE || task.state == Task.State.ERROR);
        }
    }

    public boolean clearTaskForUser(UUID taskId, UserPermissions user) {
        synchronized (tasks) {
            return tasks.get(user.email).removeIf(t -> t.id.equals(taskId));
        }
    }

    public void clearOldTasks () {
        synchronized (tasks) {
            tasks.entries().removeIf(e -> {
                Task task = e.getValue();
                return (task.state == Task.State.DONE || task.state == Task.State.ERROR) && task.durationSinceCompleted().toDays() >= 1;
            });
        }
    }

    /**
     * Just demonstrating how this would be used.
     */
    public void example () {
        enqueueHeavyTask(newTaskForUser(new UserPermissions("abyrd@conveyal.com", true, "conveyal"))
            .withTag("description", "Process some complicated things")
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

}
