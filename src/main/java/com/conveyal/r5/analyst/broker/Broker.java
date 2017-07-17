package com.conveyal.r5.analyst.broker;

import com.amazonaws.regions.*;
import com.amazonaws.regions.Region;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
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
 * This class tracks incoming requests from workers to consume Analyst tasks, and attempts to match those
 * requests to enqueued tasks. It aims to draw tasks fairly from all users, and fairly from all jobs within each user,
 * while attempting to respect the graph affinity of each worker (give it tasks that require the same graph it has been
 * working on recently).
 *
 * When no work is available or no workers are available, the polling functions return immediately, avoiding spin-wait.
 * When they are receiving no work, workers are expected to disconnect and re-poll occasionally, on the order of 30
 * seconds. This serves as a signal to the broker that they are still alive and waiting.
 *
 * TODO if there is a backlog of work (the usual case when jobs are lined up) workers will constantly change graphs.
 * Because (at least currently) two users never share the same graph, we can get by with pulling tasks cyclically or
 * randomly from all the jobs, and just actively shaping the number of workers with affinity for each graph by forcing
 * some of them to accept tasks on graphs other than the one they have declared affinity for.
 *
 * This could be thought of as "affinity homeostasis". We  will constantly keep track of the ideal proportion of workers
 * by graph (based on active queues), and the true proportion of consumers by graph (based on incoming requests) then
 * we can decide when a worker's graph affinity should be ignored and what it should be forced to.
 *
 * It may also be helpful to mark jobs every time they are skipped in the LRU queue. Each time a job is serviced,
 * it is taken out of the queue and put at its end. Jobs that have not been serviced float to the top.
 *
 * TODO: occasionally purge closed connections from workersByCategory
 * TODO: worker catalog and graph affinity homeostasis
 * TODO: catalog of recently seen consumers by affinity with IP: response.getRequest().getRemoteAddr();
 */
public class Broker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    public final CircularList<Job> jobs = new CircularList<>();

    /** The most tasks to deliver to a worker at a time. */
    public final int MAX_TASKS_PER_WORKER = 8;

    /**
     * How long to give workers to start up (in ms) before assuming that they have started (and starting more
     * on a given graph if they haven't.
     */
    public static final long WORKER_STARTUP_TIME = 60 * 60 * 1000;

    private int nWaitingConsumers = 0; // including some that might be closed

    private int nextTaskId = 0;

    /** Maximum number of workers allowed */
    private int maxWorkers;

    private static final ObjectMapper mapper = JsonUtilities.objectMapper;

    private long nextRedeliveryCheckTime = System.currentTimeMillis();

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

    /**
     * Requests that are not part of a job and can "cut in line" in front of jobs for immediate execution.
     * When a high priority task is first received, we attempt to send it to a worker right away via
     * the side channels. If that doesn't work, we put them here to be picked up the next time a worker
     * is available via normal task distribution channels.
     */
    private ArrayListMultimap<WorkerCategory, AnalysisTask> stalledHighPriorityTasks = ArrayListMultimap.create();

    /**
     * High priority requests that have just come and are about to be sent down a single point channel.
     * They put here for just 100 ms so that any that arrive together are batched to the same worker.
     * If we didn't do this, two requests arriving at basically the same time could get fanned out to
     * two different workers because the second came in in between closing the side channel and the worker
     * reopening it.
     */
    private Multimap<WorkerCategory, AnalysisTask> newHighPriorityTasks = ArrayListMultimap.create();

    /** Priority requests that have already been farmed out to workers, and are awaiting a response. */
    private TIntObjectMap<Response> highPriorityResponses = new TIntObjectHashMap<>();

    /** Outstanding requests from workers for tasks, grouped by worker graph affinity. */
    Map<WorkerCategory, Deque<Response>> workersByCategory = new HashMap<>();

    /**
     * Side channels used to send single point requests to workers, cutting in front of any other work on said workers.
     * We use a TreeMultimap because it is ordered, and the wrapped response defines an order based on
     * machine ID. This way, the same machine will tend to get all single point work for a graph,
     * so multiple machines won't stay alive to do single point work.
     */
    private Multimap<WorkerCategory, WrappedResponse> singlePointChannels = TreeMultimap.create();

    /** should we work offline */
    private boolean workOffline;

    private AmazonEC2 ec2;

    private Timer timer = new Timer();

    private String workerName, project;

    /**
     * keep track of which graphs we have launched workers on and how long ago we launched them,
     * so that we don't re-request workers which have been requested.
     */
    private TObjectLongMap<WorkerCategory> recentlyRequestedWorkers = new TObjectLongHashMap<>();

    // Queue of tasks to complete Delete, Enqueue etc. to avoid synchronizing all the functions ?
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

        // Tags for the workers
        workerName = brokerConfig.getProperty("worker-name") != null ? brokerConfig.getProperty("worker-name") : "analyst-worker";
        project = brokerConfig.getProperty("project") != null ? brokerConfig.getProperty("project") : "analyst";

        this.maxWorkers = brokerConfig.getProperty("max-workers") != null ? Integer.parseInt(brokerConfig.getProperty("max-workers")) : 4;

        ec2 = new AmazonEC2Client();

        // When running on an EC2 instance, default to the AWS region of that intance
        Region region = null;
        if (!workOffline) {
            region = Regions.getCurrentRegion();
        }
        if (region != null) {
            ec2.setRegion(region);
        }
    }

    /**
     * Enqueue a task for execution ASAP, planning to return the response over the same HTTP connection.
     * Low-reliability, no re-delivery.
     *
     * Returns true if the task was delivered and the caller should suspend the response.
     */
    public synchronized boolean enqueuePriorityTask (AnalysisTask task, Response response) {
        boolean workersAvailable = workersAvailable(task.getWorkerCategory());
        if (!workersAvailable) {
            createWorkersInCategory(task.getWorkerCategory());
            // chances are it won't be done in 30 seconds, but we want to poll frequently to avoid issues with phasing
            try {
                response.setHeader("Retry-After", "30");
                response.setStatus(202, "No workers available in this category, please retry shortly");
                Writer resWriter = response.getWriter();
                JsonUtilities.objectMapper.writeValue(resWriter, new ClusterStatus(ClusterStatus.Status.CLUSTER_STARTING_UP));
                resWriter.close();
                response.finish();
            } catch (IOException e) {
                LOG.error("Could not finish high-priority task, 202 response", e);
            }
        }

        // if we're in offline mode, enqueue anyhow to kick the cluster to build the graph
        // note that this will mean that requests get delivered multiple times in offline mode,
        // so some unnecessary computation takes place
        if (workersAvailable || workOffline) {
            task.taskId = nextTaskId++;
            newHighPriorityTasks.put(task.getWorkerCategory(), task);

            // workers aren't available, don't suspend the response
            if (workersAvailable) highPriorityResponses.put(task.taskId, response);

            // wait 100ms to deliver to workers in case another task comes in almost simultaneously
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    deliverHighPriorityTasks(task.getWorkerCategory());
                }
            }, 100);
        }

        // do not notify task delivery thread just yet as we haven't put anything in the task delivery queue yet.

        // if workers were available, the caller should wait. Otherwise just return the 202
        return workersAvailable;
    }

    /** Attempt to deliver high priority tasks via side channels, or move them into normal channels if need be. */
    public synchronized void deliverHighPriorityTasks (WorkerCategory category) {
        Collection<AnalysisTask> tasks = newHighPriorityTasks.get(category);

        if (tasks.isEmpty())
            // someone got here first
            return;

        // try to deliver via side channels
        Collection<WrappedResponse> wrs = singlePointChannels.get(category);

        if (!wrs.isEmpty()) {
            // there is (probably) a single point machine waiting to receive this
            WrappedResponse wr = wrs.iterator().next();

            try {
                wr.response.setContentType("application/json");
                OutputStream os = wr.response.getOutputStream();
                mapper.writeValue(os, tasks);
                os.close();
                wr.response.resume();

                newHighPriorityTasks.removeAll(category);

                return;
            } catch (Exception e) {
                LOG.info("Failed to deliver single point job via side channel, reverting to normal channel", e);
            } finally {
                // remove responses whether they are dead or alive
                removeSinglePointChannel(category, wr);
            }
        }

        // if we got here we didn't manage to send it via side channel, put it in the rotation for normal channels
        // not using putAll as it retains a link to the original collection and then we get a concurrent modification exception later.
        tasks.forEach(t -> stalledHighPriorityTasks.put(category, t));
        LOG.info("No side channel available for graph {}, delivering {} tasks via normal channel",
                category, tasks.size());
        newHighPriorityTasks.removeAll(category);

        // wake up delivery thread
        notify();
    }

    /** Enqueue some tasks for queued execution possibly much later. Results will be saved to S3. */
    public synchronized void enqueueTasks (List<AnalysisTask> tasks) {
        Job job = findJob(tasks.get(0)); // creates one if it doesn't exist

        if (!workersAvailable(job.getWorkerCategory())) {
            createWorkersInCategory(job.getWorkerCategory());
        }

        for (AnalysisTask task : tasks) {
            task.taskId = nextTaskId++;
            job.addTask(task);
            LOG.debug("Enqueued task id {} in job {}", task.taskId, job.jobId);
            if (!task.graphId.equals(job.workerCategory.graphId)) {
                LOG.error("Task graph ID {} does not match job: {}.", task.graphId, job.workerCategory);
            }
            if (!task.workerVersion.equals(job.workerCategory.workerVersion)) {
                LOG.error("Task R5 commit {} does not match job: {}.", task.workerVersion, job.workerCategory);
            }
        }
        // Wake up the delivery thread if it's waiting on input.
        // This wakes whatever thread called wait() while holding the monitor for this Broker object.
        notify();
    }

    public boolean workersAvailable (WorkerCategory category) {
        // Ensure we don't assign work to dead workers.
        workerCatalog.purgeDeadWorkers();

        if (workOffline) {
            return !workerCatalog.workersByGraph.get(category.graphId).isEmpty();
        } else {
            return !workerCatalog.workersByCategory.get(category).isEmpty();
        }
    }

    /** Create workers for a given job, if need be */
    public void createWorkersInCategory (WorkerCategory category) {

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
        res.getReservation().getInstances().forEach(i -> {
            Collection<Tag> tags = Arrays.asList(
                    new Tag("Name", workerName),
                    new Tag("project", project)
            );
            i.setTags(tags);
        });
        recentlyRequestedWorkers.put(category, System.currentTimeMillis());
        LOG.info("Requesting {} workers", nWorkers);
    }

    /** Consumer long-poll operations are enqueued here. */
    public synchronized void registerSuspendedResponse(WorkerCategory category, Response response) {
        // Shelf this suspended response in a queue grouped by graph affinity.
        Deque<Response> deque = workersByCategory.get(category);
        if (deque == null) {
            deque = new ArrayDeque<>();
            workersByCategory.put(category, deque);
        }
        deque.addLast(response);
        nWaitingConsumers += 1;
        // Wake up the delivery thread if it's waiting on consumers.
        // This is whatever thread called wait() while holding the monitor for this Broker object.
        notify();
    }

    /** When we notice that a long poll connection has closed, we remove it here. */
    public synchronized boolean removeSuspendedResponse(WorkerCategory category, Response response) {
        Deque<Response> deque = workersByCategory.get(category);
        if (deque == null) {
            return false;
        }
        if (deque.remove(response)) {
            nWaitingConsumers -= 1;
            LOG.debug("Removed closed connection from queue.");
            return true;
        }
        return false;
    }

    /**
     * Register an HTTP connection that can be used to send single point requests directly to
     * workers, bypassing normal task distribution channels.
     */
    public synchronized void registerSinglePointChannel (WorkerCategory category, WrappedResponse response) {
        singlePointChannels.put(category, response);
        // No need to notify as the side channels are not used by the normal task delivery loop.
    }

    /**
     * Remove a single point channel because the connection was closed.
     */
    public synchronized boolean removeSinglePointChannel (WorkerCategory category, WrappedResponse response) {
        return singlePointChannels.remove(category, response);
    }

    /**
     * See if any jobs have undelivered tasks that should be re-enqueued for delivery.
     */
    private void checkRedelivery() {
        for (Job job : jobs) {
            job.redeliver();
        }
    }

    private boolean noUndeliveredTasks() {
        for (Job job : jobs) {
            if (!job.tasksAwaitingDelivery.isEmpty()) {
                return false;
            }
        }
        // No jobs have any tasks waiting for delivery, but high priority tasks that were not delivered via the side
        // channel are stored outside the jobs.
        return stalledHighPriorityTasks.isEmpty();
    }

    /**
     * This method checks whether there are any high-priority tasks or normal job tasks and attempts to match them with
     * waiting workers.
     *
     * It blocks by calling wait() whenever it has nothing to do (when no tasks or workers available). It is awakened
     * whenever new tasks come in or when a worker (re-)connects.
     *
     * This whole function is synchronized because wait() must be called within a synchronized block. When wait() is
     * called, the monitor is released and other threads listening for worker connections or added jobs can act.
     */
    public synchronized void deliverTasks() throws InterruptedException {

        // See if any tasks failed and need to be re-enqueued.
        checkRedelivery();

        // Wait until there are some undelivered tasks.
        while (noUndeliveredTasks()) {
            LOG.debug("Task delivery thread is going to sleep, there are no tasks waiting for delivery.");
            // Thread will be notified when tasks are added or there are new incoming consumer connections.
            wait();
            // If a worker connected while there were no tasks queued for delivery,
            // we need to check if any should be re-delivered.
            checkRedelivery();
        }
        LOG.debug("Task delivery thread is awake and there are some undelivered tasks.");

        while (nWaitingConsumers == 0) {
            LOG.debug("Task delivery thread is going to sleep, there are no consumers waiting.");
            // Thread will be notified when tasks are added or there are new incoming consumer connections.
            wait();
        }
        LOG.debug("Task delivery thread awake; consumers are waiting and tasks are available");

        // Loop over all jobs and send them to consumers
        // This makes for an as-fair-as-possible allocation: jobs are fairly allocated between
        // workers on their graph.

        // start with high-priority tasks
        HIGHPRIORITY: for (Map.Entry<WorkerCategory, Collection<AnalysisTask>> e : stalledHighPriorityTasks
                .asMap().entrySet()) {
            // the collection is an arraylist with the most recently added at the end
            WorkerCategory workerCategory = e.getKey();
            Collection<AnalysisTask> tasks = e.getValue();

            // See if there are any workers that requested tasks in this category.
            // Don't respect graph affinity when working offline; we can't arbitrarily start more workers.
            Deque<Response> consumers;
            if (!workOffline) {
                consumers = workersByCategory.get(workerCategory);
            } else {
                // Working offline, just feed the task to any available worker on any category.
                Optional<Deque<Response>> opt = workersByCategory.values().stream().filter(c -> !c.isEmpty()).findFirst();
                if (opt.isPresent()) consumers = opt.get();
                else consumers = null;
            }

            if (consumers == null || consumers.isEmpty()) {
                LOG.warn("No worker found for {}, needed for {} high-priority tasks", workerCategory, tasks.size());
                continue HIGHPRIORITY;
            }

            Iterator<AnalysisTask> taskIt = tasks.iterator();
            while (taskIt.hasNext() && !consumers.isEmpty()) {
                Response consumer = consumers.pop();

                // package tasks into a job
                Job job = new Job("HIGH PRIORITY");
                job.workerCategory = workerCategory;
                for (int i = 0; i < MAX_TASKS_PER_WORKER && taskIt.hasNext(); i++) {
                    job.addTask(taskIt.next());
                    taskIt.remove();
                }

                // TODO inefficiency here: we should mix single point and multipoint in the same response
                deliver(job, consumer);
                nWaitingConsumers--;
            }
        }

        // deliver low priority tasks
        while (nWaitingConsumers > 0) {
            // ensure we advance at least one; advanceToElement will not advance if the predicate passes
            // for the first element.
            jobs.advance();

            // find a job that both has visible tasks and has available workers
            // We don't respect graph affinity when working offline, because we can't start more workers
            Job current;
            if (!workOffline) {
                current = jobs.advanceToElement(job -> !job.tasksAwaitingDelivery.isEmpty() &&
                        workersByCategory.containsKey(job.workerCategory) &&
                        !workersByCategory.get(job.workerCategory).isEmpty());
            }
            else {
                current = jobs.advanceToElement(e -> !e.tasksAwaitingDelivery.isEmpty());
            }

            // nothing to see here
            if (current == null) break;

            Deque<Response> consumers;
            if (!workOffline)
                consumers = workersByCategory.get(current.workerCategory);
            else {
                Optional<Deque<Response>> opt = workersByCategory.values().stream().filter(c -> !c.isEmpty()).findFirst();
                if (opt.isPresent()) consumers = opt.get();
                else consumers = null;
            }
            // deliver this job to only one consumer
            // This way if there are multiple workers and multiple jobs the jobs will be fairly distributed, more or less
            deliver(current, consumers.pop());
            nWaitingConsumers--;
        }

        // TODO: graph switching

        // we've delivered everything we can, prevent anything else from happening until something changes
        wait();
    }

    /**
     * This uses a linear search through jobs, which should not be problematic unless there are thousands of
     * simultaneous jobs. TODO task IDs should really not be sequential integers should they?
     * @return a Job object that contains the given task ID.
     */
    public Job getJobForTask (int taskId) {
        for (Job job : jobs) {
            if (job.containsTask(taskId)) {
                return job;
            }
        }
        return null;
    }

    /**
     * Attempt to hand some tasks from the given job to a waiting consumer connection.
     * The write will fail if the consumer has closed the connection but it hasn't been removed from the connection
     * queue yet. This can happen because the Broker methods are synchronized, and the removal action may be waiting
     * to get the monitor while we are trying to distribute tasks here.
     * @return whether the handoff succeeded.
     */
    public synchronized boolean deliver (Job job, Response response) {

        // Check up-front whether the connection is still open.
        if (!response.getRequest().getRequest().getConnection().isOpen()) {
            LOG.debug("Consumer connection was closed. It will be removed.");
            return false;
        }

        // Get up to N tasks from the tasksAwaitingDelivery deque
        List<AnalysisTask> tasks = new ArrayList<>();
        while (tasks.size() < MAX_TASKS_PER_WORKER && !job.tasksAwaitingDelivery.isEmpty()) {
            tasks.add(job.tasksAwaitingDelivery.poll());
        }

        // Attempt to deliver the tasks to the given consumer.
        try {
            response.setStatus(HttpStatus.OK_200);
            OutputStream out = response.getOutputStream();
            mapper.writeValue(out, tasks);
            response.resume();
        } catch (IOException e) {
            // The connection was probably closed by the consumer, but treat it as a server error.
            LOG.debug("Consumer connection caused IO error, it will be removed.");
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.resume();
            // Delivery failed, put tasks back on (the end of) the queue.
            job.tasksAwaitingDelivery.addAll(tasks);
            return false;
        }
        LOG.debug("Delivery of {} tasks succeeded.", tasks.size());
        job.lastDeliveryTime = System.currentTimeMillis();
        return true;
    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not be re-delivered.
     * TODO maybe use unique delivery receipts instead of task IDs to handle redelivered tasks independently
     * @return whether the task was found and removed.
     */
    public synchronized boolean markTaskCompleted (int taskId) {
        Job job = getJobForTask(taskId);
        if (job == null) {
            LOG.error("Could not find a job containing task {}, and therefore could not mark the task as completed.", taskId);
            return false;
        }
        job.completedTasks.add(taskId);
        return true;
    }

    /**
     * Marks the specified priority task as completed, and returns the suspended Response object for the connection
     * that submitted the priority task (the UI), which is probably still waiting to receive a result back over the
     * same connection. A HttpHandler thread can then pump data from the worker back to the origin of the task,
     * without blocking the broker thread.
     * TODO rename to "deregisterSuspendedTaskProducer" and "deregisterSuspendedTaskConsumer" ?
     */
    public synchronized Response deletePriorityTask (int taskId) {
        return highPriorityResponses.remove(taskId);
    }

    /** This is the broker's main event loop. */
    @Override
    public void  run() {
        while (true) {
            try {
                deliverTasks();
            } catch (InterruptedException e) {
                LOG.info("Task pump thread was interrupted.");
                return;
            }
        }
    }

    /** Find the job that should contain a given task, creating that job if it does not exist. */
    public Job findJob (AnalysisTask task) {
        Job job = findJob(task.jobId);
        if (job != null) {
            return job;
        }
        job = new Job(task.jobId);
        job.workerCategory = new WorkerCategory(task.graphId, task.workerVersion);
        jobs.insertAtTail(job);
        return job;
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

    /** Returns whether this broker is tracking any jobs that have unfinished tasks. */
    public synchronized boolean anyJobsActive() {
        for (Job job : jobs) {
            if (!job.isComplete()) return true;
        }
        return false;
    }

    /**
     * We wrap responses in a class that has a machine ID, and then put them in a TreeSet so that
     * the machine with the lowest ID on a given graph always gets single-point work. The reason
     * for this is so that a single machine will tend to get single-point work and thus we don't
     * unnecessarily keep multiple multipoint machines alive.
     */
    public static class WrappedResponse implements Comparable<WrappedResponse> {
        public final Response response;
        public final String machineId;

        public WrappedResponse(String machineId, Response response) {
            this.machineId = machineId;
            this.response = response;
        }

        @Override public int compareTo(WrappedResponse wrappedResponse) {
            return this.machineId.compareTo(wrappedResponse.machineId);
        }
    }
}
