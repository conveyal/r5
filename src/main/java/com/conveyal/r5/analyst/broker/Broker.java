package com.conveyal.r5.analyst.broker;

import com.amazonaws.regions.*;
import com.amazonaws.regions.Region;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.ByteStreams;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * This class distributes the tasks making up regional jobs to workers.
 *
 * It should aim to draw tasks fairly from all organizations, and fairly from all jobs within each organization,
 * while attempting to respect the transport network affinity of each worker, giving the worker tasks that require
 * the same network it has been using recently.
 *
 * Previously workers long-polled for work, holding lots of connections open. Now they short-poll and sleep for a while
 * if there's no work. This is simpler and allows us to work withing much more standard HTTP frameworks.
 *
 * The fact that workers continuously re-poll for work every 10-30 seconds serves as a signal to the broker that
 * they are still alive and waiting. This also allows the broker to maintain a catalog of active workers.
 *
 * Because (at least currently) two organizations never share the same graph, we can get by with pulling tasks
 * cyclically or randomly from all the jobs, and actively shape the number of workers with affinity for each graph by
 * forcing some of them to accept tasks on graphs other than the one they have declared affinity for.
 *
 * This could be thought of as "affinity homeostasis". We  will constantly keep track of the ideal proportion of workers
 * by graph (based on active jobs), and the true proportion of consumers by graph (based on incoming polling), then
 * we can decide when a worker's graph affinity should be ignored and what it should be forced to.
 *
 * It may also be helpful to mark jobs every time they are skipped in the LRU queue. Each time a job is serviced,
 * it is taken out of the queue and put at its end. Jobs that have not been serviced float to the top.
 *
 * TODO: occasionally purge dead workers from workersByCategory
 */
public class Broker {

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    // TODO replace with Multimap
    public final CircularList<Job> jobs = new CircularList<>();

    /** The most tasks to deliver to a worker at a time. */
    public final int MAX_TASKS_PER_WORKER = 8;

    /**
     * How long to give workers to start up (in ms) before assuming that they have started (and starting more
     * on a given graph if they haven't.
     */
    public static final long WORKER_STARTUP_TIME = 60 * 60 * 1000;

    /** Maximum number of workers allowed */
    private int maxWorkers;

    /*static {
        mapper.registerModule(AgencyAndIdSerializer.makeModule());
        mapper.registerModule(QualifiedModeSetSerializer.makeModule());
        mapper.registerModule(JavaLocalDateSerializer.makeModule());
        mapper.registerModule(TraverseModeSetSerializer.makeModule());
    }*/

    /** The configuration for this broker. */
    private final Properties brokerConfig;

    /** The configuration that will be applied to workers launched by this broker. */
    private Properties workerConfig;

    /** Keeps track of all the workers that have contacted this broker recently asking for work. */
    protected WorkerCatalog workerCatalog = new WorkerCatalog();

    /** Outstanding requests from workers for tasks, grouped by worker graph affinity. */
    Map<WorkerCategory, Deque<Response>> workersByCategory = new HashMap<>();

    /** should we work offline */
    private boolean workOffline;

    private AmazonEC2 ec2;

    /**
     * keep track of which graphs we have launched workers on and how long ago we launched them,
     * so that we don't re-request workers which have been requested.
     */
    private TObjectLongMap<WorkerCategory> recentlyRequestedWorkers = new TObjectLongHashMap<>();

    // TODO evaluate whether synchronizing all the functions to make this threadsafe is a performance issue.
    // We might want to allow a queue of tasks to Complete, Delete, Enqueue etc. but then we have to throttle it.

    public Broker (Properties brokerConfig, String addr, int port) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst broker starting at {}", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        this.brokerConfig = brokerConfig;

        Boolean workOffline = Boolean.parseBoolean(brokerConfig.getProperty("work-offline"));
        if (workOffline == null) workOffline = true;
        this.workOffline = workOffline;

        if (!workOffline) {
            // create a config for the AWS workers
            workerConfig = new Properties();

            if (this.brokerConfig.getProperty("worker-config") != null) {
                // load the base worker configuration if specified
                try {
                    File f = new File(this.brokerConfig.getProperty("worker-config"));
                    FileInputStream fis = new FileInputStream(f);
                    workerConfig.load(fis);
                    fis.close();
                } catch (IOException e) {
                    LOG.error("Error loading base worker configuration", e);
                }
            }

            workerConfig.setProperty("broker-address", addr);
            workerConfig.setProperty("broker-port", "" + port);

            if (brokerConfig.getProperty("statistics-queue") != null)
                workerConfig.setProperty("statistics-queue", brokerConfig.getProperty("statistics-queue"));

            workerConfig.setProperty("graphs-bucket", brokerConfig.getProperty("graphs-bucket"));
            workerConfig.setProperty("pointsets-bucket", brokerConfig.getProperty("pointsets-bucket"));

            // Tell the workers to shut themselves down automatically
            workerConfig.setProperty("auto-shutdown", "true");
        }

        this.maxWorkers = brokerConfig.getProperty("max-workers") != null ? Integer.parseInt(brokerConfig.getProperty("max-workers")) : 4;

        ec2 = new AmazonEC2Client();

        // When running on an EC2 instance, default to the AWS region of that instance
        Region region = null;
        if (!workOffline) {
            region = Regions.getCurrentRegion();
        }
        if (region != null) {
            ec2.setRegion(region);
        }
    }

    /**
     * Enqueue a set of tasks for a regional analysis.
     * Only a single task is passed in, and the broker expands it into all the individual tasks for a regional job.
     */
    public synchronized void enqueueTasksForRegionalJob(RegionalTask templateTask) {
        LOG.info("Enqueuing tasks for job {} using template task.", templateTask.jobId);
        if (findJob(templateTask.jobId) != null) {
            LOG.error("Someone tried to enqueue job {} but it already exists.", templateTask.jobId);
            throw new RuntimeException("Enqueued duplicate job " + templateTask.jobId);
        }
        Job job = new Job(templateTask);
        jobs.insertAtTail(job);
        if (!workersAvailable(job.workerCategory)) {
            // FIXME whoa, we're sending requests to EC2 inside a synchronized block that stops the whole broker!
            createWorkersInCategory(job.workerCategory);
        }
    }

    private boolean workersAvailable (WorkerCategory category) {
        // Ensure we don't assign work to dead workers.
        workerCatalog.purgeDeadWorkers();

        if (workOffline) {
            return !workerCatalog.workersByGraph.get(category.graphId).isEmpty();
        } else {
            return !workerCatalog.workersByCategory.get(category).isEmpty();
        }
    }

    /** Create workers for a given job, if need be */
    private void createWorkersInCategory (WorkerCategory category) {

        String clientToken = UUID.randomUUID().toString().replaceAll("-", "");

        if (workOffline) {
            LOG.info("Work offline enabled, not creating workers for {}", category);
            return;
        }

        if (workerCatalog.observationsByWorkerId.size() >= maxWorkers) {
            LOG.warn("{} workers already started, not starting more; jobs will not complete on {}", maxWorkers, category);
            return;
        }

        // If workers have already been started up, don't repeat the operation.
        if (recentlyRequestedWorkers.containsKey(category)
                && recentlyRequestedWorkers.get(category) >= System.currentTimeMillis() - WORKER_STARTUP_TIME){
            LOG.info("Workers still starting on {}, not starting more", category);
            return;
        }

        // TODO: should we start multiple workers on large jobs?
        int nWorkers = 1;

        // There are no workers on this graph with the right worker commit, start some.
        LOG.info("Starting {} workers as there are none on {}", nWorkers, category);
        RunInstancesRequest req = new RunInstancesRequest();
        req.setImageId(brokerConfig.getProperty("ami-id"));
        req.setInstanceType(InstanceType.valueOf(brokerConfig.getProperty("worker-type")));
        req.setSubnetId(brokerConfig.getProperty("subnet-id"));

        // even if we can't get all the workers we want at least get some
        req.setMinCount(1);
        req.setMaxCount(nWorkers);

        // It's fine to just modify the worker config without a protective copy because this method is synchronized.
        workerConfig.setProperty("initial-graph-id", category.graphId);
        // Tell the worker where to get its R5 JAR. This is a Conveyal S3 bucket with HTTP access turned on.
        String workerDownloadUrl = String.format("https://r5-builds.s3.amazonaws.com/%s.jar",
                category.workerVersion);

        ByteArrayOutputStream cfg = new ByteArrayOutputStream();
        try {
            workerConfig.store(cfg, "Worker config");
            cfg.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Read in the startup script
        // We used to just pass the config to custom AMI, but by constructing a startup script that initializes a stock
        // Amazon Linux AMI, we don't have to worry about maintaining and keeping our AMI up to date. Amazon Linux applies
        // important security updates on startup automatically.
        try {
            String workerConfigString = cfg.toString();
            InputStream scriptIs = Broker.class.getClassLoader().getResourceAsStream("worker.sh");
            ByteArrayOutputStream scriptBaos = new ByteArrayOutputStream();
            ByteStreams.copy(scriptIs, scriptBaos);
            scriptIs.close();
            scriptBaos.close();
            String scriptTemplate = scriptBaos.toString();

            String logGroup = workerConfig.getProperty("log-group");

            String script = MessageFormat.format(scriptTemplate, workerDownloadUrl, logGroup, workerConfigString);

            // Send the config to the new workers as EC2 "user data"
            String userData = new String(Base64.getEncoder().encode(script.getBytes()));
            req.setUserData(userData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (brokerConfig.getProperty("worker-iam-role") != null)
            req.setIamInstanceProfile(new IamInstanceProfileSpecification().withArn(brokerConfig.getProperty("worker-iam-role")));

        // launch into a VPC if desired
        if (brokerConfig.getProperty("subnet") != null)
            req.setSubnetId(brokerConfig.getProperty("subnet"));

        // allow us to retry request at will
        req.setClientToken(clientToken);
        // allow machine to shut itself completely off
        req.setInstanceInitiatedShutdownBehavior(ShutdownBehavior.Terminate);
        RunInstancesResult res = ec2.runInstances(req);
        // FIXME this was not pushing the instance tags to EC2, just changing them locally
        recentlyRequestedWorkers.put(category, System.currentTimeMillis());
        LOG.info("Requesting {} workers", nWorkers);
    }

    /**
     * Attempt to find some tasks that match what a worker is requesting.
     * Always returns a list, which may be empty if there is nothing to deliver.
     */
    public synchronized List<AnalysisTask> getSomeWork (WorkerCategory workerCategory) {
        Job job;
        // FIXME use workOffline boolean instead of examining workerVersion
        if (workerCategory.graphId == null || "UNKNOWN".equalsIgnoreCase(workerCategory.workerVersion)) {
            // This worker has no loaded networks or no specified version, get tasks from the first job that has any.
            // FIXME Note that this is ignoring worker version number! This is useful for debugging though.
            job = jobs.advanceToElement(j -> j.hasTasksToDeliver());
        } else {
            // This worker has a preferred network, get tasks from a job on that network.
            job = jobs.advanceToElement(j -> j.workerCategory.equals(workerCategory) && j.hasTasksToDeliver());
        }
        if (job == null) {
            // No matching job was found.
            return Collections.EMPTY_LIST;
        }
        // Get up to N tasks from the tasksAwaitingDelivery deque
        return job.generateSomeTasksToDeliver(MAX_TASKS_PER_WORKER);
    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not be re-delivered.
     * The result of the computation is supplied.
     * TODO separate completion out from returning the work product, since they have different synchronization requirements
     * this would also allow returning errors as JSON and the grid result separately.
     * @return whether the task was found and removed.
     */
    public synchronized boolean markTaskCompleted (RegionalWorkResult workResult) {
        String jobId = workResult.jobId;
        int taskId = workResult.taskId;
        Job job = findJob(jobId);
        if (job == null) {
            LOG.error("Could not find a job with ID {} and therefore could not mark the task as completed.", jobId);
            return false;
        }
        if (!job.markTaskCompleted(taskId)) {
            LOG.error("Failed to mark task {} completed on job {}.", taskId, jobId);
        }
        // Once the last task is marked as completed, the job is finished. Purge it from the list to free memory.
        if (job.isComplete()) {
            job.verifyComplete();
            jobs.remove(job);
        }
        return true;
    }

    /** Find the job for the given jobId, returning null if that job does not exist. */
    public Job findJob (String jobId) {
        for (Job job : jobs) {
            if (job.jobId.equals(jobId)) {
                return job;
            }
        }
        return null;
    }

    /** Delete the job with the given ID. */
    public synchronized boolean deleteJob (String jobId) {
        Job job = findJob(jobId);
        if (job == null) return false;
        return jobs.remove(job);
    }

    /**
     * Given a worker commit and network, return the IP or DNS name of that worker.
     */
    public String getWorkerAddress(WorkerCategory workerCategory) {
        Collection<String> workerIds = workerCatalog.workersByCategory.get(workerCategory);
        for (String workerId : workerIds) {
            WorkerObservation observation = workerCatalog.observationsByWorkerId.get(workerId);
            return observation.status.ipAddress;
        }
        // Fall back on any existing worker
        for (WorkerObservation observation : workerCatalog.observationsByWorkerId.values()) {
            return observation.status.ipAddress;
        }
        return "localhost";
    }


    /**
     * Get a collection of all the workers that have recently reported to this broker.
     * The returned objects are designed to be serializable so they can be returned over an HTTP API.
     */
    public Collection<WorkerObservation> getWorkerObservations () {
        return workerCatalog.observationsByWorkerId.values();
    }

    /**
     * Get a collection of all unfinished jobs being managed by this broker.
     * The returned objects are designed to be serializable so they can be returned over an HTTP API.
     */
    public Collection<JobStatus> getJobSummary() {
        List<JobStatus> jobStatusList = new ArrayList<>();
        for (Job job : this.jobs) {
            jobStatusList.add(new JobStatus(job));
        }
        // Add a summary of all jobs to the list.
        jobStatusList.add(new JobStatus(jobStatusList));
        return jobStatusList;
    }

    /**
     * Record information that a worker sent about itself.
     */
    public void recordWorkerObservation(WorkerStatus workerStatus) {
        workerCatalog.catalog(workerStatus);
    }

}
