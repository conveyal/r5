package com.conveyal.analysis.components;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
 */
public class TaskScheduler implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(TaskScheduler.class);

    private final ScheduledExecutorService scheduledExecutor;
    private final ExecutorService lightExecutor;
    private final ExecutorService heavyExecutor;

    // Keep the futures returned when tasks are scheduled, which give access to status information and exceptions.
    // This should facilitate debugging. We may want to do the same thing for one-off tasks.
    private final List<ScheduledFuture> periodicTaskFutures = new ArrayList<>();

    public interface Config {
        int lightThreads ();
        int heavyThreads ();
    }

    /** Interface for all actions that we want to repeat at regular intervals. */
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

}
