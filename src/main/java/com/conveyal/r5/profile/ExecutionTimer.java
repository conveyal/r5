package com.conveyal.r5.profile;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * Encapsulates the logic for timing execution, accumulating measurements of many separate operations, and logging the
 * results. Each ExecutionTimer can have children, which are assumed to only be running when their parent is running.
 *
 * All times are tracked in nanoseconds. Several sources indicate that nanoTime() should always be used for timing
 * execution rather than System.currentTimeMillis(). And some of the operations we're timing are significantly
 * sub-millisecond in duration. (Arguably on large numbers of operations using msec would be fine because the number of
 * times we cross a millisecond boundary would be proportional to the portion of a millisecond that operation took, but
 * nanoTime() avoids the problem entirely.)
 *
 * TODO ability to dump to JSON or JsonNode tree for inclusion in response, via some kind of request-scoped context
 *  object. This should help enable continuous performance tracking in CI.
 */
public class ExecutionTimer {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionTimer.class);

    public final String name;

    private boolean running = false;

    private long startTimeNanos = 0;

    private long accumulatedDurationNanos = 0;

    private final List<ExecutionTimer> children = new ArrayList<>();

    public ExecutionTimer (String name) {
        this.name = name;
    }

    public ExecutionTimer (ExecutionTimer parent, String name) {
        this.name = name;
        parent.children.add(this);
    }

    public void start () {
        checkState(!running, "Start can only be called on a stopped timer.");
        startTimeNanos = System.nanoTime();
        running = true;
    }

    public void stop () {
        checkState(running, "Stop can only be called after start.");
        long elapsed = System.nanoTime() - startTimeNanos;
        accumulatedDurationNanos += elapsed;
        running = false;
        startTimeNanos = 0;
    }

    public void timeExecution (Runnable runnable) {
        this.start();
        runnable.run();
        this.stop();
    }

    public String getMessage () {
        String description = running ? "[RUNNING]" : accumulatedDurationNanos / 1e9D + "s";
        return String.format("%s: %s", name, description);
    }

    /** Root timer is logged at INFO level, and details of children at DEBUG level. */
    public void log (int indentLevel) {
        if (indentLevel == 0) {
            LOG.info(getMessage());
        } else {
            String indent = Strings.repeat("- ", indentLevel);
            LOG.debug(indent + getMessage());
        }
    }

    public void logWithChildren () {
        logWithChildren(0);
    }

    public void logWithChildren (int indentLevel) {
        log(indentLevel);
        if (children.size() > 0) {
            long otherTime = accumulatedDurationNanos;
            for (ExecutionTimer child : children) {
                otherTime -= child.accumulatedDurationNanos;
                child.logWithChildren(indentLevel + 1);
            }
            if (otherTime > 0) {
                ExecutionTimer otherTimer = new ExecutionTimer("other");
                otherTimer.accumulatedDurationNanos = otherTime;
                otherTimer.log(indentLevel + 1);
            }
        }
    }

    @Override
    public String toString () {
        return String.format("ExecutionTimer {%s}", getMessage());
    }

}
