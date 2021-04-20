package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.Model;
import com.conveyal.r5.util.ExceptionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This is a draft for a more advanced task progress system. It is not yet complete.
 * Task is intended for background tasks whose progress the end user should be aware of, such as file uploads.
 * It should not be used for automatic internal actions (such as Events) which would clutter a user's active task list.
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
 * TODO rename to BackgroundTask
 */
public class Task implements Runnable, ProgressListener {

    public static enum State {
        QUEUED, ACTIVE, DONE, ERROR
    }

    /** Every has an ID so the UI can update tasks it already knows about with new information after polling. */
    public final UUID id = UUID.randomUUID();

    // User and group are only relevant on the backend. On workers, we want to show network or cost table build progress
    // to everyone in the organization, even if someone else's action kicked off the process.
    // We could also store the full UserPermissions object instead of breaking it into fields.

    public String user;

    private String group;

    // Timestamps to track elapsed execution time and wait time.

    private Instant enqueued;

    private Instant began;

    private Instant completed;

    private int bubbleUpdateFrequency = 0;

    private int logFrequency = 0;

    /** An unchanging, human readable name for this task. */
    public final String title;

    /** Text describing the current work, which changes over the course of task processing. */
    public String description;

    public int totalWorkUnits;

    public int currentWorkUnit;

    public State state;

    /** To be on the safe side all tasks are considered heavyweight unless we explicitly set them to be lightweight. */
    private boolean isHeavy = true;

    /** Like a runnable, encapsulating the actual work that this task will perform. */
    private TaskAction action;

    // For operations that may perform a lot of fast copies?
    // Maybe instead we should just always pre-calculate how many fast copies will happen, log that before even starting,
    // and only track progress on the slow ones.
    public int nWorkUnitsSkipped;

    private Throwable throwable;

    /** How often (every how many work units) this task will log progress on the backend. */
    private int loggingFrequency;

    private Task parentTask;

    // Maybe TObjectIntMap<Task> to store subtask weights.
    public List<Task> subTasks;

    // For non-hierarchical chaining? This may be needed even if our executor is backed by a queue,
    // to prevent simultaneous execution of sequential tasks.
    public Task nextTask;

    /** Private constructor to encourage use of fluent methods. */
    private Task (String title) {
        this.title = title;
        // It's not strictly accurate to mark the task enqueued before finish constructing it and actually submit it,
        // but this avoids needing to expose that state transition and enforce calling it later.
        markEnqueued();
    }

    public double getPercentComplete() {
        return (currentWorkUnit * 100D) / totalWorkUnits;
    }

    List<Task> subtasks = new ArrayList<>();

    // TODO find a better way to set this than directly inside a closure
    public WorkProduct workProduct;

    public void addSubtask (Task subtask) {

    }

    private void markEnqueued () {
        enqueued = Instant.now();
        description = "Waiting...";
        state = State.QUEUED;
    }

    private void markActive () {
        began = Instant.now();
        this.state = State.ACTIVE;
    }

    // Because of the subtask / next task mechanism, we don't let the actions mark tasks complete.
    // This is another reason to pass the actions only a limited progress reporting interface.
    private void markComplete () {
        completed = Instant.now();
        // Force progress bars to display 100% whenever an action completes successfully.
        currentWorkUnit = totalWorkUnits;
        description = "Completed.";
        state = State.DONE;
    }

    private void markError (Throwable throwable) {
        completed = Instant.now();
        description = ExceptionUtils.shortCauseString(throwable);
        state = State.ERROR;
    }

    public boolean isHeavy () {
        return this.isHeavy;
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
        markActive();
        try {
            this.action.action(this);
            // for (Task subtask : subtasks) subtask.run();
            markComplete();
        } catch (Throwable t) {
            // TODO Store error in work product and write product to Mongo uniformly
            markError(t);
        }
    }

    @Override
    public void beginTask(String description, int totalElements) {
        // In the absence of subtasks we can call this repeatedly on the same task, which will cause the UI progress
        // bar to reset to zero at each stage, while keeping the same top level title.
        this.description = description;
        this.totalWorkUnits = totalElements;
        this.currentWorkUnit = 0;
    }

    @Override
    public void increment (int n) {
        currentWorkUnit += n;
        if (currentWorkUnit >= totalWorkUnits || currentWorkUnit < 0) {
            currentWorkUnit = totalWorkUnits - 1;
        }
    }

    // Methods for reporting elapsed times over API

    public Duration durationInQueue () {
        Instant endTime = (began == null) ? Instant.now() : began;
        return Duration.between(enqueued, endTime);
    }

    public Duration durationExecuting () {
        if (began == null) return Duration.ZERO;
        Instant endTime = (completed == null) ? Instant.now() : completed;
        return Duration.between(began, endTime);
    }

    public Duration durationComplete () {
        if (completed == null) return Duration.ZERO;
        return Duration.between(completed, Instant.now());
    }

    // FLUENT METHODS FOR CONFIGURING

    /** Call this static factory to begin building a task. */
    public static Task create (String title) {
        Task task = new Task(title);
        return task;
    }

    public Task forUser (String user) {
        this.user = user;
        return this;
    }

    public Task inGroup (String group) {
        this.group = group;
        return this;
    }

    public Task forUser (UserPermissions userPermissions) {
        return this.forUser(userPermissions.email).inGroup(userPermissions.accessGroup);
    }

    public Task withAction (TaskAction action) {
        this.action = action;
        return this;
    }

    // We can't return the WorkProduct from TaskAction, that would be disrupted by throwing exceptions.
    // It is also awkward to make a method to set it on ProgressListener - it's not really progress.
    // So we set it directly on the task before submitting it. Requires pre-setting (not necessarily storing) Model._id.
    public Task withWorkProduct (Model model) {
        this.workProduct = WorkProduct.forModel(model);
        return this;
    }

    /** Ideally we'd just pass in a Model, but currently we have two base classes, also see WorkProduct.forModel(). */
    public Task withWorkProduct (WorkProductType type, String id, String region) {
        this.workProduct = new WorkProduct(type, id, region);
        return this;
    }

    public Task setHeavy (boolean heavy) {
        this.isHeavy = heavy;
        return this;
    }

    /** Convert a single internal Task object to its representation for JSON serialization and return to the UI. */
    public ApiTask toApiTask () {
        ApiTask apiTask = new ApiTask();
        apiTask.id = id; // This can be the same as the workProduct ID except for cases with no Mongo document
        apiTask.title = title;
        apiTask.detail = description;
        apiTask.state = state;
        apiTask.percentComplete = (int) getPercentComplete();
        apiTask.secondsActive = (int) durationExecuting().getSeconds();
        apiTask.secondsComplete = (int) durationComplete().getSeconds();
        apiTask.workProduct = workProduct;
        return apiTask;
    }

}
