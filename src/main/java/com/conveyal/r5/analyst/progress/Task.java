package com.conveyal.r5.analyst.progress;

import com.conveyal.r5.util.ExceptionUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * This is a draft for a more advanced task progress system. It is not yet complete or functional.
 *
 * A Task (or some interface that it implements) could be used by the AsyncLoader to track progress. Together with some
 * AsyncLoader functionality it will be a bit like a Future with progress reporting. Use of AsyncLoader could then be
 * generalized to building TranportNetworks, EgressCostTables, etc. We don't want to run too far with lazy Async loading
 * though, since the end goal is for users to trigger builds manually.
 *
 * Each task should be able to have subtasks, each of which represents an expected percentage of the parent task.
 * Or an expected number of "relative work units" if new sub-tasks will be added on the fly, so absolutes are not known.
 * Tasks should implement ProgressListener functionality, and bubble up progress to parent tasks.
 *
 * This class serves simultaneously as an internal domain object for tracking task execution, and an external API model
 * object for communicating relevant information to the web UI (when serialized to JSON).
 */
public class Task implements Runnable, ProgressListener {

    /** Every has an ID so the UI can update tasks it already knows about with new information after polling. */
    public final UUID id = UUID.randomUUID();

    // Timestamps to track elapsed execution time and wait time.

    private final Instant enqueued = Instant.now();

    private Instant began;

    private Instant completed;

    private int bubbleUpdateFrequency = 0;

    private int logFrequency = 0;

    public int totalWorkUnits = 1;

    public int currentWorkUnit = 0;

    public final Map<String, String> tags = new HashMap<>();

    static class LogEntry {
        public final long time = Instant.now().toEpochMilli();
        public final Level level;
        public final String message;
        public LogEntry (String message, Level level) {
            this.message = message;
            this.level = level;
        }
    }
    public final List<LogEntry> log = new ArrayList<>();

    public enum State {
        QUEUED, ACTIVE, DONE, ERROR
    }

    public State state = State.QUEUED;

    /**
     * Like a runnable, encapsulating the actual work that this task will perform.
     */
    private TaskAction action;

    // For operations that may perform a lot of fast copies?
    // Maybe instead we should just always pre-calculate how many fast copies will happen, log that before even starting,
    // and only track progress on the slow ones.
    // public int nWorkUnitsSkipped;

    private Throwable throwable;

    /** How often (every how many work units) this task will log progress on the backend. */
    private int loggingFrequency;

    private Task parentTask;

    // Maybe TObjectIntMap<Task> to store subtask weights.
    public List<Task> subTasks;

    // For non-hierarchical chaining? This may be needed even if our executor is backed by a queue,
    // to prevent simultaneous execution of sequential tasks.
    // public Task nextTask;

    public double getPercentComplete() {
        return (currentWorkUnit * 100D) / totalWorkUnits;
    }

    @JsonIgnore
    List<Task> subtasks = new ArrayList<>();

    private void markActive () {
        began = Instant.now();
        this.state = State.ACTIVE;
    }

    // Because of the subtask / next task mechanism, we don't let the actions mark tasks complete.
    // This is another reason to pass the actions only a limited progress reporting interface.
    private void markComplete (State newState) {
        completed = Instant.now();
        state = newState;
    }

    /**
     * Abort the current task and cancel any subtasks or following tasks.
     * @param throwable the reason for aborting this task.
     */
    public void abort (Throwable throwable) {
        String asString = ExceptionUtils.shortAndLongString(throwable);
        this.log.add(new LogEntry(asString, Level.SEVERE));
        this.throwable = throwable;
        markComplete(State.ERROR);
    }

    protected void bubbleUpProgress() {
        // weight progress of each subtask
        double totalWeight = 0;
        double totalProgress = 0;
        for (Task task : subtasks) {
            // Task weight could also be a property of the task itself.
            double weight = 1; // fetch weight
            totalWeight += weight;
            double weightedProgress = (task.currentWorkUnit * weight) / task.totalWorkUnits;
            totalProgress += weightedProgress;
        }
        totalProgress /= totalWeight;
        // do something with total progress...
        parentTask.bubbleUpProgress();
    }

    /**
     * Check that all necessary fields have been set before enqueueing for execution, and check any invariants.
     */
    public void validate () {
        // etc.
    }

    @Override
    public void run () {
        // The main action is run before the subtasks. It may not make sense progress reporting-wise for tasks to have
        // both their own actions and subtasks with their own actions. Perhaps series of tasks are a special kind of
        // action, which should encapsulate the bubble-up progress computation.
        markActive();
        try {
            this.action.action(this);
        } catch (Throwable t) {
            abort(t);
            return;
        }

        for (Task subtask : subtasks) {
            subtask.run();
        }
        markComplete(State.DONE);
    }

    @Override
    public void beginTask(String description, int totalElements) {
        // Just using an existing interface that may eventually be modified to not include this method.
        this.logEntry(description);
    }

    @Override
    public void increment () {
        this.currentWorkUnit += 1;
        // Occasionally bubble up progress to parent tasks, log to console, etc.
        if (parentTask != null) {
            if (this.bubbleUpdateFrequency > 0 && (currentWorkUnit % bubbleUpdateFrequency == 0)) {
                parentTask.bubbleUpProgress();
            }
        }
        if (this.logFrequency > 0 && (currentWorkUnit % logFrequency == 0)) {
            // LOG.info...
        }
    }

    public void logEntry(String entry) {
        this.log.add(new LogEntry(entry, Level.INFO));
    }

    // Methods for reporting elapsed times over API

    public long getSecondsInQueue () {
        return durationInQueue().toSeconds();
    }

    public long getSecondsExecuting () {
        return durationExecuting().toSeconds();
    }

    public Duration durationInQueue () {
        Instant endTime = (began == null) ? Instant.now() : began;
        return Duration.between(enqueued, endTime);
    }

    public Duration durationExecuting () {
        if (began == null) return Duration.ZERO;
        Instant endTime = (completed == null) ? Instant.now() : completed;
        return Duration.between(began, endTime);
    }

    public Duration durationSinceCompleted () {
        if (completed == null) return null;
        return Duration.between(completed, Instant.now());
    }

    // FLUENT METHODS FOR CONFIGURING

    public Task withTag (String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    /**
     * TODO have the TaskAction set the total work units via the restricted ProgressListener interface.
     * The number of work units is meaningless until we're calculating and showing progress.
     * Allow both setting and incrementing the number of work units for convenience.
     */
    public Task withTotalWorkUnits (int totalWorkUnits) {
        this.totalWorkUnits = totalWorkUnits;
        this.bubbleUpdateFrequency = totalWorkUnits / 100;
        return this;
    }

    public Task withAction(TaskAction action) {
        this.action = action;
        return this;
    }

    public Task withLogEntry(String message) {
        this.logEntry(message);
        return this;
    }
}
