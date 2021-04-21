package com.conveyal.analysis.components;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.r5.analyst.progress.ApiTask;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.analyst.progress.TaskAction;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 * This also serves to report active tasks for a given user.
 * That could be considered a separate function, but it ended up having a bidirectional dependency on this
 * TaskScheduler so they've been merged.
 *
 * This is moving in the direction of having a single unified task management and reporting system across the backend
 * and workers. It could be interesting to gather task status from the whole cluster of workers and merge them together
 * into one view. This could conceivably even include regional analyses and chunks of work for regional analyses on
 * workers. But even if such merging doesn't occur, it will be convenient to always report progress from backend and
 * workers to the UI in the same data structures.
 *
 * So we're moving toward a programming API where you submit Tasks with attached Actions.
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

    // Keep the futures returned when periodic tasks are scheduled, giving access to status information and exceptions.
    // This should facilitate debugging. We may want to do the same thing for one-off tasks.
    // This will need to be synchronized if we ever allow canceling periodic tasks.
    private final List<ScheduledFuture> periodicTaskFutures = new ArrayList<>();

    // Keep track of tasks submitted by each user, for reporting on their progress over the HTTP API. The collection is
    // synchronized because multiple users may add to and read this map from different HTTP server threads. When
    // reading be aware of the synchronization requirements described on Guava Multimaps.synchronizedMultimap.
    // As a Guava SynchronizedMultimap, certain compound operations such as forEach do properly lock the entire multimap.
    // Calls to get() return a Guava SynchronizedSet (a subtype of SynchronizedCollection) which also properly locks the
    // entire parent multimap for the duration of compound operations such as forEach and removeIf. However it appears
    // that stream operations must be manually synchronized. And it seems like there is a potential for another thread
    // to alter the map between a call to get() and a subsequent synchronized call like forEach(). When in doubt it
    // usually can't hurt to wrap a series of operations in synchronized(tasksForUser).
    private final SetMultimap<String, Task> tasksForUser = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    // Maybe the number of threads should always be auto-set from the number of processor cores (considering SMT).
    public interface Config {
        int lightThreads ();
        int heavyThreads ();
    }

    /**
     * Interface for all actions that we want to repeat at regular intervals.
     * This could be merged with all other task actions, using a getPeriodSeconds method returning -1 for non-periodic.
     * However this would yield interfaces with more than one method, and single-method interfaces provide for some
     * syntactic flexibility (lambdas and method references).
     * TODO use PeriodicTasks to handle worker record scavenging and cluster stats reporting.
     * TODO Heavy/light/periodic should be indicated on the Task rather than the TaskAction passed in.
     * TODO ProgressListener might benefit from methods to markComplete() and reportError(Throwable)
     */
    public interface PeriodicTask extends Runnable {
        int getPeriodSeconds();
    }

    public TaskScheduler (Config config) {
        scheduledExecutor = Executors.newScheduledThreadPool(1);
        lightExecutor = Executors.newFixedThreadPool(config.lightThreads());
        heavyExecutor = Executors.newFixedThreadPool(config.heavyThreads());
    }

    public void repeatRegularly (PeriodicTask periodicTask) {
        String className = periodicTask.getClass().getSimpleName();
        int periodSeconds = periodicTask.getPeriodSeconds();
        LOG.info("An instance of {} will run every {} seconds.", className, periodSeconds);
        ErrorTrap wrappedPeriodicTask = new ErrorTrap(periodicTask);
        periodicTaskFutures.add(
            scheduledExecutor.scheduleAtFixedRate(wrappedPeriodicTask, periodSeconds, periodSeconds, TimeUnit.SECONDS)
        );
    }

    public void repeatRegularly (PeriodicTask... periodicTasks) {
        for (PeriodicTask task : periodicTasks) {
            repeatRegularly(task);
        }
    }

    // TODO these methods can be eliminated in favor of the single enqueue method that gets information about
    //      heavy/light/periodic from its Task parameter.

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

    /**
     * Return the status of all background tasks for the given user, as API model objects for serialization to JSON.
     * Completed tasks that finished over a minute ago will be purged after returning them. This ensures they're sent
     * at least once to the UI and gives any other tabs a chance to poll for them.
     * Conversion to the API model is done here to allow synchronization without copying the list of internal tasks.
     * The task scheduler collections are being updated by worker threads. The fields of the individual Tasks may also
     * be updated at any time. So there is a risk of sending a partially updated Task out to the UI. If this ever causes
     * problems we'll need to lock each Task independently.
     * Returns an empty list even when no tasks have been recorded for the user (return is always non-null).
     */
    public @Nonnull List<ApiTask> getTasksForUser (String userEmail) {
        synchronized (tasksForUser) {
            Set<Task> tasks = tasksForUser.get(userEmail);
            if (tasks == null) return Collections.emptyList();
            List<ApiTask> apiTaskList = tasks.stream()
                    .map(Task::toApiTask)
                    .collect(Collectors.toUnmodifiableList());
            tasks.removeIf(t -> t.durationComplete().getSeconds() > 60);
            return apiTaskList;
        }
    }

    // Q: Should the caller ever create its own Tasks, or if are tasks created here inside the TaskScheduler from
    // other raw information? Having the caller creating a Task seems like a good way to configure execution details
    // like heavy/light/periodic, and submit user information without passing it in. That task request could be separate
    // from the internal Task object it creates, but that seems like overkill for an internal mechanism.
    public void newTaskForUser (UserPermissions user, TaskAction taskAction) {
        Task task = Task.create("TITLE").forUser(user).withAction(taskAction);
        enqueue(task);
    }

    public void enqueue (Task task) {
        task.validate();
        tasksForUser.put(task.user, task);
        // TODO if task.isPeriodic()... except that maybe user tasks should never be periodic. Document that.
        if (task.isHeavy()) {
            heavyExecutor.submit(new ErrorTrap(task)); // TODO replicate ErrorTrap inside Task
        } else {
            lightExecutor.submit(new ErrorTrap(task));
        }
    }

    /** Just demonstrating how this can be used. */
    public void example () {
        this.enqueue(Task.create("Example").forUser(new UserPermissions("abyrd@conveyal.com", true, "conveyal"))
            .withAction((progressListener -> {
                progressListener.beginTask("Processing complicated things...", 1024);
                double sum = 0;
                for (int i = 0; i < 1024; i++) {
                    sum += Math.sqrt(i);
                    progressListener.increment();
                }
            }))
       );
    }

    /** Get the number of slower "heavy" tasks that are queued for execution and not yet being processed. */
    public int getBacklog() {
        return ((ThreadPoolExecutor) heavyExecutor).getQueue().size();
    }

}
