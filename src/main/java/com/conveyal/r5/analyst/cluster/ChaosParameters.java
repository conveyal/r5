package com.conveyal.r5.analyst.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static com.conveyal.r5.analyst.cluster.ChaosParameters.FailureMode.*;

/**
 * If these parameters are included in an AnalysisWorkerTask, they will cause intentional failures and disruptions on
 * the workers processing them. This is used in testing the robustness of the system against unexpected failures,
 * particularly worker shutdown when using lower priced spot instances. This is a simple form of "fault injection" or
 * "chaos engineering".
 *
 * For testing redelivery of tasks, we cannot simply designate specific task IDs to fail, either explicitly or
 * parametrically, or even through deterministic pseudo-random number sequences, because these same tasks would keep
 * failing on each redelivery. Whether the tasks are skipped over or they cause a more complete worker shutdown, no
 * number of retries could solve the problem.
 *
 * The alternative (currently in use) is to fail randomly a certain percentage of the time. Here there is some chance
 * the actual number of failures will deviate far below the target percentage, and may even be zero. In this case a
 * full regional analysis would complete without ever triggering the error, so it's not ideal for automated testing.
 * The probability of an ineffective test run is like the probability of getting a run of all heads in a coin toss:
 * (1-p)^N.
 *
 * I believe that in a distributed system like ours, the only practical way to ensure a specific number of failures on
 * a set of tasks that do not recur identically at each retry / redistribution of tasks is for the backend to
 * orchestrate (perhaps precompute) which failures will happen, checking them off as they are distributed to workers to
 * ensure certain constraints are respected.
 *
 * An imperative, non-probabilistic instruction to fail would be attached to certain individual tasks.
 * Failing only on the initial round of task delivery would not be sufficient. We want to test that we can recover from
 * multiple redelivery rounds of faulty processing.
 *
 * One particular case could be tested by setting a flag on a single task that would shut down the whole worker
 * processing it. But this is still essentially orchestrating the test from the backend rather than at the workers.
 */
public class ChaosParameters {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosParameters.class);

    private static final Random random = new Random();

    public FailureMode failureMode;

    public int startingAtTask = 0;

    public int failurePercent;

    /** This message will be logged when the failure is injected, and will be included in any exception thrown. */
    public String message = "Intentional failure for testing purposes.";

    private boolean shouldFail (int taskIndex) {
        return taskIndex >= startingAtTask && random.nextInt(100) < failurePercent;
    }

    public boolean shouldDropTaskBeforeCompute (int taskIndex) {
        boolean shouldDrop = failureMode == DROP_TASK && shouldFail(taskIndex);
        if (shouldDrop) {
            LOG.warn("Intentionally dropping task for testing purposes.");
        }
        return shouldDrop;
    }

    public void considerShutdownOrException (int taskIndex) {
        if (shouldFail(taskIndex)) {
            if (failureMode == THROW_EXCEPTION) {
                LOG.warn("Intentionally throwing runtime exception for testing purposes.");
                throw new RuntimeException(message);
            } else if (failureMode == EXIT_JVM) {
                // This exits cleanly - also allow other nonzero return codes?
                LOG.warn("Intentionally exiting JVM for testing purposes.");
                System.exit(0);
            }
        }
    }

    /**
     * Our worker scripts do not contain a loop to restart the JVM. They shut down whenever the JVM exits.
     * If for some reason we want another mode to directly shut down the machine, this can be done with something like:
     * Runtime.getRuntime().exec("sudo shutdown -h now");
     * But this is operating system specific and requires more checks for exceptions and process completion.
     * A NO_FAILURE value could be added for an implementation that does not depend on null checks.
     * We may also want to add a failure mode that makes some tasks very slow to process (sleep for N seconds).
     */
    public enum FailureMode {
        DROP_TASK,
        THROW_EXCEPTION,
        EXIT_JVM
    }

}
