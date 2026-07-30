package com.conveyal.analysis.components.broker;

import com.conveyal.r5.analyst.WorkerCategory;

import java.util.List;

/// An immutable snapshot of facts about one worker category, including tasks in any jobs in that
/// category and workers currently polling for work on that category.
public class WorkerDemand {

    public final WorkerCategory category;

    /// Tags applied to any worker instances started for this category, taken from one of its active jobs.
    public final WorkerTags workerTags;

    /// The number of workers in this category that have recently polled the backend.
    public final int activeWorkerCount;

    /// The number of tasks across all active jobs for which a worker has returned a result.
    public final int tasksCompleted;

    /// The number of tasks across all active jobs not yet marked complete.
    public final int tasksOutstanding;

    /// How many of the outstanding tasks include transit in their computation.
    /// Transit tasks imply more computation per origin than street-only tasks.
    public final int transitTasksOutstanding;

    // These boolean flags indicate tasks using new or experimental features that historically
    // capped fleet size to avoid runaway situations. Such caps only ever limited how many workers
    // were requested, never how many actually worked on a job (consider existing workers from a
    // previous job in the same category) so never quite worked as intended. The protected features
    // are also no longer so new or experimental. Enforcing per-job concurrency at task delivery
    // to workers would be a much better mechanism if we want to maintain these protections.

    public final boolean freeFormOrigins;

    public final boolean includePathResults;

    /// Aggregate facts about all the given jobs, which should be the active jobs in the given category.
    public WorkerDemand (WorkerCategory category, int activeWorkerCount, List<Job> activeJobs) {
        this.category = category;
        this.activeWorkerCount = activeWorkerCount;
        this.workerTags = activeJobs.get(0).workerTags;
        int completed = 0;
        int outstanding = 0;
        int transitOutstanding = 0;
        boolean freeForm = false;
        boolean paths = false;
        for (Job job : activeJobs) {
            int jobOutstanding = job.nTasksTotal - job.nTasksCompleted;
            completed += job.nTasksCompleted;
            outstanding += jobOutstanding;
            if (job.templateTask.hasTransit()) {
                transitOutstanding += jobOutstanding;
            }
            freeForm |= job.templateTask.originPointSet != null;
            paths |= job.templateTask.includePathResults;
        }
        this.tasksCompleted = completed;
        this.tasksOutstanding = outstanding;
        this.transitTasksOutstanding = transitOutstanding;
        this.freeFormOrigins = freeForm;
        this.includePathResults = paths;
    }

    @Override
    public String toString () {
        return String.format(
            "[%s: %d tasks outstanding (%d transit), %d complete, %d active workers]",
            category, tasksOutstanding, transitTasksOutstanding, tasksCompleted, activeWorkerCount
        );
    }

}
