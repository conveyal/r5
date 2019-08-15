package com.conveyal.r5.analyst.progress;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    // User and group are only relevant on the backend. On workers, we want to show network or cost table build progress
    // to everyone in the organization, even if someone else's action kicked off the process.

    private String user;

    private String group;

    // Timestamps to track execution time and wait time.

    private Instant enqueued;

    private Instant began;

    private Instant completed;

    private int bubbleUpdateFrequency = 0;

    private int logFrequency = 0;

    public String description;

    public int totalWorkUnits;

    public int currentWorkUnit;

    /** To be on the safe side all tasks are considered heavyweight unless we explicitly set them to be lightweight. */
    private boolean isHeavy = true;

    /** Like a runnable, encapsulating the actual work that this task will perform. */
    private TaskAction action;

    // For operations that may perform a lot of fast copies?
    // Maybe instead we should just always pre-calculate how many fast copies will happen, log that before even starting,
    // and only track progress on the slow ones.
    public int nWorkUnitsSkipped;

    public boolean errored;

    private Throwable throwable;

    /** How often (every how many work units) this task will log progress on the backend. */
    private int loggingFrequency;

    @JsonIgnore
    private Task parentTask;

    // Maybe TObjectIntMap<Task> to store subtask weights.
    public List<Task> subTasks;

    // For non-hierarchical chaining? This may be needed even if our executor is backed by a queue,
    // to prevent simultaneous execution of sequential tasks.
    public Task nextTask;

    public double getPercentComplete() {
        return (currentWorkUnit * 100D) / totalWorkUnits;
    }

    List<Task> subtasks = new ArrayList<>();

    /**
     * Private constructor to encourage use of fluent methods.
     */
    private Task () {}

    public void addSubtask (Task subtask) {

    }

    // Because of the subtask / next task mechanism, we don't let the actions mark tasks complete.
    // This is another reason to pass the actions only a limited progress reporting interface.
    private void markComplete () {
        this.completed = Instant.now();
    }

    public boolean isHeavy () {
        return this.isHeavy;
    }

    /**
     * Abort the current task and cancel any subtasks or following tasks.
     * @param throwable the reason for aborting this task.
     */
    public void abort (Throwable throwable) {
        this.throwable = throwable;
        // LOG?
        this.markComplete();
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
     * Check that all necesary fields have been set before enqueueing for execution, and check any invariants.
     */
    public void validate () {
        if (this.user == null) {
            throw new AssertionError("Task must have a defined user.");
        }
        // etc.
    }

    @Override
    public void run () {
        // The main action is run before the subtasks. It may not make sense progress reporting-wise for tasks to have
        // both their own actions and subtasks with their own actions. Perhaps series of tasks are a special kind of
        // action, which should encapsulate the bubble-up progress computation.
        this.action.action(this);
        for (Task subtask : subtasks) {
            subtask.run();
        }
        this.markComplete();
        this.nextTask.run();
    }

    @Override
    public void beginTask(String description, int totalElements) {
        // Just using an existing interface that may eventually be modified to not include this method.
        throw new UnsupportedOperationException();
    }

    @Override
    public void increment () {
        this.currentWorkUnit += 1;
        // Occasionally bubble up progress to parent tasks, log to console, etc.
        if (this.bubbleUpdateFrequency > 0 && (currentWorkUnit % bubbleUpdateFrequency == 0)) {
            parentTask.bubbleUpProgress();
        }
        if (this.logFrequency > 0 && (currentWorkUnit % logFrequency == 0)) {
            // LOG.info...
        }
    }

    // FLUENT METHODS FOR CONFIGURING

    // Really we should make a User object that combines user and group fields.
    public Task forUser (String user, String group) {
        this.user = user;
        this.group = group;
        return this;
    }

    public Task withDescription (String description) {
        this.description = description;
        return this;
    }

    /**
     * We may actually want the TaskAction to set the total work units via its restricted ProgressReporter interface.
     */
    public Task withTotalWorkUnits (int totalWorkUnits) {
        this.totalWorkUnits = totalWorkUnits;
        this.bubbleUpdateFrequency = totalWorkUnits / 100;
        return this;
    }

    public Task withAction (TaskAction action) {
        this.action = action;
        return this;
    }

    public Task setHeavy (boolean heavy) {
        this.isHeavy = heavy;
        return this;
    }

    public static Task newTask () {
        return new Task();
    }

}
