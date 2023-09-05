package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.components.Component;
import com.conveyal.analysis.components.WorkerLauncher;
import com.conveyal.analysis.components.eventbus.ErrorEvent;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent;
import com.conveyal.analysis.components.eventbus.WorkerEvent;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.results.MultiOriginAssembler;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.util.ExceptionUtils;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import gnu.trove.TCollections;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.CANCELED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.COMPLETED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.STARTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Action.REQUESTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.REGIONAL;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.SINGLE_POINT;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class distributes the tasks making up regional jobs to workers.
 * <p>
 * It should aim to draw tasks fairly from all organizations, and fairly from all jobs within each
 * organization, while attempting to respect the transport network affinity of each worker, giving
 * the worker tasks that require the same network it has been using recently.
 * <p>
 * Previously workers long-polled for work, holding lots of connections open. Now they short-poll
 * and sleep for a while if there's no work. This is simpler and allows us to work withing much more
 * standard HTTP frameworks.
 * <p>
 * The fact that workers continuously re-poll for work every 10-30 seconds serves as a signal to the
 * broker that they are still alive and waiting. This also allows the broker to maintain a catalog
 * of active workers.
 * <p>
 * Because (at least currently) two organizations never share the same graph, we can get by with
 * pulling tasks cyclically or randomly from all the jobs, and actively shape the number of workers
 * with affinity for each graph by forcing some of them to accept tasks on graphs other than the one
 * they have declared affinity for.
 * <p>
 * This could be thought of as "affinity homeostasis". We  will constantly keep track of the ideal
 * proportion of workers by graph (based on active jobs), and the true proportion of consumers by
 * graph (based on incoming polling), then we can decide when a worker's graph affinity should be
 * ignored and what it should be forced to.
 * <p>
 * It may also be helpful to mark jobs every time they are skipped in the LRU queue. Each time a job
 * is serviced, it is taken out of the queue and put at its end. Jobs that have not been serviced
 * float to the top.
 * <p>
 * Most methods on this class are synchronized, because they can be called from many HTTP handler
 * threads at once.
 *
 * TODO evaluate whether synchronizing all methods to make this threadsafe is a performance issue.
 */
public class Broker implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    public interface Config {
        // TODO Really these first two should be WorkerLauncher / Compute config
        boolean offline ();
        int maxWorkers ();
        boolean testTaskRedelivery ();
    }

    private Config config;

    // Component Dependencies
    private final FileStorage fileStorage;
    private final EventBus eventBus;
    private final WorkerLauncher workerLauncher;

    private final ListMultimap<WorkerCategory, Job> jobs =
            MultimapBuilder.hashKeys().arrayListValues().build();

    /** The most tasks to deliver to a worker at a time. */
    public final int MAX_TASKS_PER_WORKER = 16;

    /**
     * Used when auto-starting spot instances. Set to a smaller value to increase the number of
     * workers requested automatically
     */
    public final int TARGET_TASKS_PER_WORKER_TRANSIT = 800;
    public final int TARGET_TASKS_PER_WORKER_NONTRANSIT = 4_000;

    /**
     * We want to request spot instances to "boost" regional analyses after a few regional task
     * results are received for a given workerCategory. Do so after receiving results for an
     * arbitrary task toward the beginning of the job
     */
    public final int AUTO_START_SPOT_INSTANCES_AT_TASK = 42;

    /** The maximum number of spot instances allowable in an automatic request */
    public final int MAX_WORKERS_PER_CATEGORY = 250;

    /**
     * How long to give workers to start up (in ms) before assuming that they have started (and
     * starting more on a given graph if they haven't.
     */
    public static final long WORKER_STARTUP_TIME = 60 * 60 * 1000;


    /** Keeps track of all the workers that have contacted this broker recently asking for work. */
    private WorkerCatalog workerCatalog = new WorkerCatalog();

    /**
     * These objects piece together results received from workers into one regional analysis result
     * file per job.
     */
    private static Map<String, MultiOriginAssembler> resultAssemblers = new HashMap<>();

    /**
     * keep track of which graphs we have launched workers on and how long ago we launched them, so
     * that we don't re-request workers which have been requested.
     */
    public TObjectLongMap<WorkerCategory> recentlyRequestedWorkers =
            TCollections.synchronizedMap(new TObjectLongHashMap<>());

    public Broker (Config config, FileStorage fileStorage, EventBus eventBus, WorkerLauncher workerLauncher) {
        this.config = config;
        this.fileStorage = fileStorage;
        this.eventBus = eventBus;
        this.workerLauncher = workerLauncher;
    }

    /**
     * Enqueue a set of tasks for a regional analysis.
     * Only a single task is passed in, which the broker will expand into all the individual tasks for a regional job.
     */
    public synchronized void enqueueTasksForRegionalJob (RegionalAnalysis regionalAnalysis) {

        // Make a copy of the regional task inside the RegionalAnalysis, replacing the scenario with a scenario ID.
        RegionalTask templateTask = templateTaskFromRegionalAnalysis(regionalAnalysis);

        LOG.info("Enqueuing tasks for job {} using template task.", templateTask.jobId);
        if (findJob(templateTask.jobId) != null) {
            LOG.error("Someone tried to enqueue job {} but it already exists.", templateTask.jobId);
            throw new RuntimeException("Enqueued duplicate job " + templateTask.jobId);
        }
        WorkerTags workerTags = WorkerTags.fromRegionalAnalysis(regionalAnalysis);
        Job job = new Job(templateTask, workerTags);
        jobs.put(job.workerCategory, job);

        // Register the regional job so results received from multiple workers can be assembled into one file.
        // TODO encapsulate MultiOriginAssemblers in a new Component
        // Note: if this fails with an exception we'll have a job enqueued, possibly being processed, with no assembler.
        // That is not catastrophic, but the user may need to recognize and delete the stalled regional job.
        MultiOriginAssembler assembler = new MultiOriginAssembler(regionalAnalysis, job, fileStorage);
        resultAssemblers.put(templateTask.jobId, assembler);

        if (config.testTaskRedelivery()) {
            // This is a fake job for testing, don't confuse the worker startup code below with null graph ID.
            return;
        }

        if (workerCatalog.noWorkersAvailable(job.workerCategory, config.offline())) {
            createOnDemandWorkerInCategory(job.workerCategory, workerTags);
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            recentlyRequestedWorkers.remove(job.workerCategory);
        }
        eventBus.send(new RegionalAnalysisEvent(templateTask.jobId, STARTED).forUser(workerTags.user, workerTags.group));
    }

    /**
     * The single RegionalTask object represents a lot of individual accessibility tasks at many different origin
     * points, typically on a grid. Before passing that RegionalTask on to the Broker (which distributes tasks to
     * workers and tracks progress), we remove the details of the scenario, substituting the scenario's unique ID
     * to save time and bandwidth. This avoids repeatedly sending the scenario details to the worker in every task,
     * as they are often quite voluminous. The workers will fetch the scenario once from S3 and cache it based on
     * its ID only. We protectively clone this task because we're going to null out its scenario field, and don't
     * want to affect the original object which contains all the scenario details.
     * TODO Why is all this detail added after the Persistence call?
     *      We don't want to store all the details added below in Mongo?
     */
    private RegionalTask templateTaskFromRegionalAnalysis (RegionalAnalysis regionalAnalysis) {
        RegionalTask templateTask = regionalAnalysis.request.clone();
        // First replace the inline scenario with a scenario ID, storing the scenario for retrieval by workers.
        Scenario scenario = templateTask.scenario;
        templateTask.scenarioId = scenario.id;
        // Null out the scenario in the template task, avoiding repeated serialization to the workers as massive JSON.
        templateTask.scenario = null;
        String fileName = String.format("%s_%s.json", regionalAnalysis.bundleId, scenario.id);
        FileStorageKey fileStorageKey = new FileStorageKey(BUNDLES, fileName);
        try {
            File localScenario = FileUtils.createScratchFile("json");
            JsonUtil.objectMapper.writeValue(localScenario, scenario);
            // FIXME this is using a network service in a method called from a synchronized broker method.
            //  Move file into storage before entering the synchronized block.
            fileStorage.moveIntoStorage(fileStorageKey, localScenario);
        } catch (IOException e) {
            LOG.error("Error storing scenario for retrieval by workers.", e);
        }
        // Fill in all the fields in the template task that will remain the same across all tasks in a job.
        // I am not sure why we are re-setting all these fields, it seems like they are already set when the task is
        // initialized by AnalysisRequest.populateTask. But we'd want to thoroughly check that assumption before
        // eliminating or moving these lines.
        templateTask.jobId = regionalAnalysis._id;
        templateTask.graphId = regionalAnalysis.bundleId;
        templateTask.workerVersion = regionalAnalysis.workerVersion;
        templateTask.height = regionalAnalysis.height;
        templateTask.width = regionalAnalysis.width;
        templateTask.north = regionalAnalysis.north;
        templateTask.west = regionalAnalysis.west;
        templateTask.zoom = regionalAnalysis.zoom;
        return templateTask;
    }

    /**
     * Create on-demand worker for a given job.
     */
    public void createOnDemandWorkerInCategory(WorkerCategory category, WorkerTags workerTags){
        createWorkersInCategory(category, workerTags, 1, 0);
    }

    /**
     * Create on-demand/spot workers for a given job, after certain checks
     * @param nOnDemand EC2 on-demand instances to request
     * @param nSpot Target number of EC2 spot instances to request. The actual number requested may be lower if the
     *              total number of workers running is approaching the maximum specified in the Broker config.
     */
    public void createWorkersInCategory (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot) {

        // Log error messages rather than throwing exceptions, as this code often runs in worker poll handlers.
        // Throwing an exception there would not report any useful information to anyone.

        if (config.offline()) {
            LOG.info("Work offline enabled, not creating workers for {}.", category);
            return;
        }

        if (nOnDemand < 0 || nSpot < 0) {
            LOG.error("Negative number of workers requested, not starting any.");
            return;
        }

        final int nRequested = nOnDemand + nSpot;
        if (nRequested <= 0) {
            LOG.error("No workers requested, not starting any.");
            return;
        }

        // Zeno's worker pool management: never start more than half the remaining capacity.
        final int remainingCapacity = config.maxWorkers() - workerCatalog.totalWorkerCount();
        final int maxToStart = remainingCapacity / 2;
        if (maxToStart <= 0) {
            LOG.error("Due to capacity limiting, not starting any workers.");
            return;
        }

        if (nRequested > maxToStart) {
            LOG.warn("Request for {} workers is more than half the remaining worker pool capacity.", nRequested);
            nSpot = maxToStart;
            nOnDemand = 0;
            LOG.warn("Lowered to {} on-demand and {} spot workers.", nOnDemand, nSpot);
        }

        // Just an assertion for consistent state - this should never happen.
        // Re-sum nOnDemand + nSpot here instead of using nTotal, as they may have been revised.
        if (workerCatalog.totalWorkerCount() + nOnDemand + nSpot > config.maxWorkers()) {
            LOG.error(
                "Starting workers would exceed the maximum capacity of {}. Jobs may stall on {}.",
                config.maxWorkers(),
                category
            );
            return;
        }

        // If workers have already been started up, don't repeat the operation.
        if (recentlyRequestedWorkers.containsKey(category)
                && recentlyRequestedWorkers.get(category) >= System.currentTimeMillis() - WORKER_STARTUP_TIME) {
            LOG.debug("Workers still starting on {}, not starting more", category);
            return;
        }

        workerLauncher.launch(category, workerTags, nOnDemand, nSpot);

        // Record the fact that we've requested an on-demand worker so we don't do it repeatedly.
        if (nOnDemand > 0) {
            recentlyRequestedWorkers.put(category, System.currentTimeMillis());
        }
        if (nSpot > 0) {
            eventBus.send(new WorkerEvent(REGIONAL, category, REQUESTED, nSpot).forUser(workerTags.user, workerTags.group));
        }
        if (nOnDemand > 0) {
            eventBus.send(new WorkerEvent(SINGLE_POINT, category, REQUESTED, nOnDemand).forUser(workerTags.user, workerTags.group));
        }
        LOG.info("Requested {} on-demand and {} spot workers on {}", nOnDemand, nSpot, category);
    }

    /**
     * Attempt to find some tasks that match what a worker is requesting.
     * Always returns a list, which may be empty if there is nothing to deliver.
     */
    public synchronized List<RegionalTask> getSomeWork (WorkerCategory workerCategory) {
        Job job;
        if (config.offline()) {
            // Working in offline mode; get tasks from the first job that has any tasks to deliver.
            job = jobs.values().stream()
                    .filter(j -> j.hasTasksToDeliver()).findFirst().orElse(null);
        } else {
            // This worker has a preferred network, get tasks from a job on that network.
            job = jobs.get(workerCategory).stream()
                    .filter(j -> j.hasTasksToDeliver()).findFirst().orElse(null);
        }
        if (job == null) {
            // No matching job was found.
            return Collections.EMPTY_LIST;
        }
        // Return up to N tasks that are waiting to be processed.
        return job.generateSomeTasksToDeliver(MAX_TASKS_PER_WORKER);
    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not
     * be re-delivered. The result of the computation is supplied. This could potentially be merged
     * with handleRegionalWorkResult, but they have different synchronization requirements.
     * TODO separate marking complete from returning the work product, since they have different
     *      synchronization requirements. This would also allow returning errors as JSON and the
     *      grid result separately.
     *
     * @return whether the task was found and removed.
     */
    public synchronized void markTaskCompleted (Job job, int taskId) {
        checkNotNull(job);
        if (!job.markTaskCompleted(taskId)) {
            LOG.error("Failed to mark task {} completed on job {}.", taskId, job.jobId);
        }
        // Once the last task is marked as completed, the job is finished.
        // Remove it and its associated result assembler from the maps.
        // The caller should already have a reference to the result assembler so it can process the final results.
        if (job.isComplete()) {
            job.verifyComplete();
            jobs.remove(job.workerCategory, job);
            resultAssemblers.remove(job.jobId);
            eventBus.send(new RegionalAnalysisEvent(job.jobId, COMPLETED).forUser(job.workerTags.user, job.workerTags.group));
        }
    }

    /**
     * When job.errors is non-empty, job.isErrored() becomes true and job.isActive() becomes false.
     * The Job will stop delivering tasks, allowing workers to shut down, but will continue to exist allowing the user
     * to see the error message. User will then need to manually delete it, which will remove the result assembler.
     * This method ensures synchronization of writes to Jobs from the unsynchronized worker poll HTTP handler.
     */
    private synchronized void recordJobError (Job job, String error) {
        if (job != null) {
            job.errors.add(error);
        }
    }

    /**
     * Simple method for querying all current job statuses.
     * @return List of JobStatuses
     */
    public synchronized Collection<JobStatus> getAllJobStatuses () {
        TObjectIntMap<String> workersPerJob = workerCatalog.activeWorkersPerJob();
        Collection<JobStatus> jobStatuses = new ArrayList<>();
        for (Job job : jobs.values()) {
            JobStatus jobStatus = new JobStatus(job);
            jobStatus.activeWorkers = workersPerJob.get(job.jobId);
            jobStatuses.add(jobStatus);
        }
        return jobStatuses;
    }

    /** Find the job for the given jobId, returning null if that job does not exist. */
    public synchronized Job findJob (String jobId) {
        return jobs.values().stream()
                .filter(job -> job.jobId.equals(jobId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Delete the job with the given ID.
     */
    public synchronized boolean deleteJob (String jobId) {
        // Remove the job from the broker so we stop distributing its tasks to workers.
        Job job = findJob(jobId);
        if (job == null) return false;
        boolean success = jobs.remove(job.workerCategory, job);
        // Shut down the object used for assembling results, removing its associated temporary disk file.
        // TODO just put the assembler in the Job object
        MultiOriginAssembler assembler = resultAssemblers.remove(jobId);
        try {
            assembler.terminate();
        } catch (Exception e) {
            LOG.error(
                "Could not terminate grid result assembler, this may waste disk space. Reason: {}",
                e.toString()
            );
            success = false;
        }
        eventBus.send(new RegionalAnalysisEvent(job.jobId, CANCELED).forUser(job.workerTags.user, job.workerTags.group));
        // Note updateByUserIfPermitted in caller, which deletes regional analysis from Persistence
        return success;
    }

    /**
     * Given a worker commit ID and transport network, return the IP or DNS name of a worker that has that software
     * and network already loaded. If none exist, return null. The caller can then try to start one.
     */
    public synchronized String getWorkerAddress(WorkerCategory workerCategory) {
        if (config.offline()) {
            return "localhost";
        }
        // First try to get a worker that's already loaded the right network.
        // This value will be null if no workers exist in this category - caller should attempt to create some.
        String workerAddress = workerCatalog.getSinglePointWorkerAddressForCategory(workerCategory);
        return workerAddress;
    }


    /**
     * Get a collection of all the workers that have recently reported to this broker.
     * The returned objects are designed to be serializable so they can be returned over an HTTP API.
     */
    public Collection<WorkerObservation> getWorkerObservations () {
        return workerCatalog.getAllWorkerObservations();
    }

    public synchronized void unregisterSinglePointWorker (WorkerCategory category) {
        workerCatalog.tryToReassignSinglePointWork(category);
    }

    /**
     * Record information that a worker sent about itself.
     */
    public void recordWorkerObservation(WorkerStatus workerStatus) {
        workerCatalog.catalog(workerStatus);
    }

    /**
     * Slots a single regional work result received from a worker into the appropriate position in the appropriate
     * files. Also considers requesting extra spot instances after a few results have been received.
     * @param workResult an object representing accessibility results for a single origin point, sent by a worker.
     */
    public void handleRegionalWorkResult(RegionalWorkResult workResult) {
        // Retrieving the job and assembler from their maps is not thread safe, so we use synchronized block here.
        // Once the job is retrieved, it can be used below to requestExtraWorkersIfAppropriate without synchronization,
        // because that method only uses final fields of the job.
        Job job = null;
        MultiOriginAssembler assembler;
        try {
            synchronized (this) {
                job = findJob(workResult.jobId);
                assembler = resultAssemblers.get(workResult.jobId);
                if (job == null || assembler == null || !job.isActive()) {
                    // This will happen naturally for all delivered tasks after a job is deleted or it errors out.
                    LOG.debug("Ignoring result for unrecognized, deleted, or inactive job ID {}.", workResult.jobId);
                    return;
                }
                if (workResult.error != null) {
                    // Record any error reported by the worker and don't pass bad results on to regional result assembly.
                    recordJobError(job, workResult.error);
                    return;
                }
                // Mark tasks completed first before passing results to the assembler. On the final result received,
                // this will minimize the risk of race conditions by quickly making the job invisible to incoming stray
                // results from spurious redeliveries, before the assembler is busy finalizing and uploading results.
                markTaskCompleted(job, workResult.taskId);
            }
            // Unlike everything above, result assembly (like starting workers below) does not synchronize on the broker.
            // It contains some slow nested operations to move completed results into storage. Really we should not do
            // these things synchronously in an HTTP handler called by the worker. We should probably synchronize this
            // entire method, then somehow enqueue slower async completion and cleanup tasks in the caller.
            assembler.handleMessage(workResult);
        } catch (Throwable t) {
            recordJobError(job, ExceptionUtils.stackTraceString(t));
            eventBus.send(new ErrorEvent(t));
            return;
        }
        // When non-error results are received for several tasks we assume the regional analysis is running smoothly.
        // Consider accelerating the job by starting an appropriate number of EC2 spot instances.
        if (workResult.taskId == AUTO_START_SPOT_INSTANCES_AT_TASK) {
            requestExtraWorkersIfAppropriate(job);
        }
    }

    private void requestExtraWorkersIfAppropriate(Job job) {
        WorkerCategory workerCategory = job.workerCategory;
        int categoryWorkersAlreadyRunning = workerCatalog.countWorkersInCategory(workerCategory);
        if (categoryWorkersAlreadyRunning < MAX_WORKERS_PER_CATEGORY) {
            // TODO more refined determination of number of workers to start (e.g. using observed tasks per minute
            //  for recently completed tasks -- but what about when initial origins are in a desert/ocean?)
            int targetWorkerTotal;
            if (job.templateTask.hasTransit()) {
                // Total computation for a task with transit depends on the number of stops and whether the
                // network has frequency-based routes. The total computation for the job depends on these
                // factors as well as the number of tasks (origins). Zoom levels add a complication: the number of
                // origins becomes an even poorer proxy for the number of stops. We use a scale factor to compensate
                // -- all else equal, high zoom levels imply fewer stops per origin (task) and a lower ideal target
                // for number of workers. TODO reduce scale factor further when there are no frequency routes. But is
                //  this worth adding a field to Job or RegionalTask?
                float transitScaleFactor = (9f / job.templateTask.zoom);
                targetWorkerTotal = (int) ((job.nTasksTotal / TARGET_TASKS_PER_WORKER_TRANSIT) * transitScaleFactor);
            } else {
                // Tasks without transit are simpler. They complete relatively quickly, and the total computation for
                // the job increases roughly with linearly with the number of origins.
                targetWorkerTotal = job.nTasksTotal / TARGET_TASKS_PER_WORKER_NONTRANSIT;
            }

            // Do not exceed the limit on workers per category TODO add similar limit per accessGroup or user
            targetWorkerTotal = Math.min(targetWorkerTotal, MAX_WORKERS_PER_CATEGORY);
            // Guardrails until freeform pointsets are tested more thoroughly
            if (job.templateTask.originPointSet != null) targetWorkerTotal = Math.min(targetWorkerTotal, 80);
            if (job.templateTask.includePathResults) targetWorkerTotal = Math.min(targetWorkerTotal, 20);
            int nSpot =  targetWorkerTotal - categoryWorkersAlreadyRunning;
            createWorkersInCategory(job.workerCategory, job.workerTags, 0, nSpot);
        }
    }

    public synchronized boolean anyJobsActive () {
        for (Job job : jobs.values()) {
            if (job.isActive()) return true;
        }
        return false;
    }

    public synchronized void logJobStatus() {
        for (Job job : jobs.values()) {
            LOG.info(job.toString());
        }
    }

}
