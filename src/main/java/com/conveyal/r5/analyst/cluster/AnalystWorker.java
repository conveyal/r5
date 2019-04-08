package com.conveyal.r5.analyst.cluster;

import com.amazonaws.regions.Regions;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.FilePersistence;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.S3FilePersistence;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.transitive.TransitiveNetwork;
import com.conveyal.r5.util.AsyncLoader;
import com.conveyal.r5.util.ExceptionUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.io.LittleEndianDataOutputStream;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This is a main class run by worker machines in our Analysis computation cluster.
 * It polls a broker requesting work over HTTP, telling the broker what networks and scenarios it has loaded.
 * When it receives some work from the broker it does the necessary work and returns the results back to the front
 * end via the broker.
 *
 * The worker can poll for work over two different channels. One is for large asynchronous batch jobs, the other is
 * intended for interactive single point requests that should return as fast as possible.
 *
 * TODO rename AnalysisWorker <---
 */
public class AnalystWorker implements Runnable {

    /**
     * Worker ID - just a random ID so we can differentiate machines used for computation.
     * Useful to isolate the logs from a particular machine, as well as to evaluate any
     * variation in performance coming from variation in the performance of the underlying
     * VMs.
     *
     * This needs to be static so the logger can access it; see the static member class
     * WorkerIdDefiner. A side effect is that only one worker can run in a given JVM. It also
     * needs to be defined before the logger is defined, so that it is initialized before the
     * logger is.
     *
     * TODO use the per-thread slf4j ID feature
     * Actually by setting the thread name / creating a new thread maybe we can get around this somehow.
     */
    public static final String machineId = UUID.randomUUID().toString().replaceAll("-", "");

    private static final Logger LOG = LoggerFactory.getLogger(AnalystWorker.class);

    private static final String DEFAULT_BROKER_ADDRESS = "localhost";

    private static final String DEFAULT_BROKER_PORT = "7070";

    public static final int POLL_WAIT_SECONDS = 15;

    public static final int POLL_MAX_RANDOM_WAIT = 5;

    /** The port on which the worker will listen for single point tasks forwarded from the backend. */
    public static final int WORKER_LISTEN_PORT = 7080;

    /**
     * The number of threads the worker will use to receive HTTP connections. This crudely limits memory consumption
     * from the worker handling single point requests.
     * Unfortunately we can't set this very low. We get a message saying we need at least 10 threads:
     * max=2 < needed(acceptors=1 + selectors=8 + request=1)
     * TODO find a more effective way to limit simultaneous computations, e.g. feed them through the regional thread pool.
     */
    public static final int WORKER_SINGLE_POINT_THREADS = 10;

    // TODO make non-static and make implementations swappable
    // This is very ugly because it's static but initialized at class instantiation.
    public static FilePersistence filePersistence;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    public final NetworkPreloader networkPreloader;

    /**
     * If this is true, the worker will not actually do any work. It will just report all tasks as completed
     * after a small delay, but will fail to do so on the given percentage of tasks. This is used in testing task
     * re-delivery and overall broker sanity with multiple jobs and multiple failing workers.
     */
    private final boolean testTaskRedelivery;

    /** In the type of tests described above, this is how often the worker will fail to return a result for a task. */
    public static final int TESTING_FAILURE_RATE_PERCENT = 20;

    /** The amount of time (in minutes) a worker will stay alive after starting certain work */
    static final int PRELOAD_KEEPALIVE_MINUTES = 90;
    static final int REGIONAL_KEEPALIVE_MINUTES = 2;
    static final int SINGLE_KEEPALIVE_MINUTES = 60;

    /** Clock time (milliseconds since epoch) at which the worker should be considered idle */
    long shutdownAfter;
    boolean inPreloading;

    void adjustShutdownClock (int keepAliveMinutes) {
        long t = System.currentTimeMillis() + keepAliveMinutes * 60 * 1000;
        if (inPreloading) {
            inPreloading = false;
            shutdownAfter = t;
        } else {
            shutdownAfter = Math.max(shutdownAfter, t);
        }
    }

    /** Whether this worker should shut down automatically when idle. */
    public final boolean autoShutdown;

    public static final Random random = new Random();

    /** The common root of all API URLs contacted by this worker, e.g. http://localhost:7070/api/ */
    protected String brokerBaseUrl;

    /** The HTTP client the worker uses to contact the broker and fetch regional analysis tasks. */
    static final HttpClient httpClient = makeHttpClient();

    /**
     * This timeout should be longer than the longest expected worker calculation for a single-point request.
     * Of course when linking a large grid, a worker could take much longer. We're just going to have to accept
     * timeouts in those situations until we implement fail-fast 202 responses from workers for long lived operations.
     */
    private static final int HTTP_CLIENT_TIMEOUT_SEC = 30;

    /**
     * The results of finished work accumulate here, and will be sent in batches back to the broker.
     * All access to this field should be synchronized since it will is written to by multiple threads.
     * We don't want to just wrap it in a SynchronizedList because we need an atomic copy-and-empty operation.
     */
    private List<RegionalWorkResult> workResults = new ArrayList<>();

    /** The last time (in milliseconds since the epoch) that we polled for work. */
    private long lastPollingTime;

    /** Keep track of how many tasks per minute this worker is processing, broken down by scenario ID. */
    ThroughputTracker throughputTracker = new ThroughputTracker();

    /**
     * This has been pulled out into a method so the broker can also make a similar http client.
     */
    public static HttpClient makeHttpClient () {
        PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager();
        mgr.setDefaultMaxPerRoute(20);
        int timeoutMilliseconds = HTTP_CLIENT_TIMEOUT_SEC * 1000;
        SocketConfig cfg = SocketConfig.custom()
                .setSoTimeout(timeoutMilliseconds)
                .build();
        mgr.setDefaultSocketConfig(cfg);
        return HttpClients.custom().disableAutomaticRetries()
                .setConnectionManager(mgr)
                .build();
    }

    /**
     * A loading cache of opportunity dataset grids (not grid pointsets or linkages).
     * TODO use the WebMercatorGridExtents in these Grids.
     */
    GridCache gridCache;

    /** The transport network this worker already has loaded, and therefore prefers to work on. */
    String networkId = null;

    /** Information about the EC2 instance (if any) this worker is running on. */
    EC2Info ec2info;

    /** If true Analyst is running locally, do not use internet connection and remote services such as S3. */
    private boolean workOffline;

    /**
     * A queue to hold a backlog of regional analysis tasks.
     * This avoids "slow joiner" syndrome where we wait to poll for more work until all N fetched tasks have finished,
     * but one of the tasks takes much longer than all the rest.
     * This should be long enough to hold all that have come in - we don't need to block on polling the manager.
     */
    private ThreadPoolExecutor regionalTaskExecutor;

    /** The HTTP server that receives single-point requests. */
    private spark.Service sparkHttpService;

    public static AnalystWorker forConfig (Properties config) {
        // FIXME why is there a separate configuration parsing section here? Why not always make the cache based on the configuration?
        // FIXME why is some configuration done here and some in the constructor?
        boolean workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        String awsRegion = workOffline ? null : config.getProperty("aws-region");
        String graphsBucket = workOffline ? null : config.getProperty("graphs-bucket");
        String graphDirectory = config.getProperty("cache-dir", "cache/graphs");
        TransportNetworkCache cache = new TransportNetworkCache(awsRegion, graphsBucket, new File(graphDirectory));
        return new AnalystWorker(config, cache);
    }

    public AnalystWorker(Properties config, TransportNetworkCache transportNetworkCache) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker {} starting at {}", machineId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        // PARSE THE CONFIGURATION TODO move configuration parsing into a separate method.

        testTaskRedelivery = Boolean.parseBoolean(config.getProperty("test-task-redelivery", "false"));

        // Region region = Region.getRegion(Regions.fromName(config.getProperty("aws-region")));
        filePersistence = new S3FilePersistence(config.getProperty("aws-region"));

        // First, check whether we are running Analyst offline.
        workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        if (workOffline) {
            LOG.info("Working offline. Avoiding internet connections and hosted services.");
        }

        {
            String brokerAddress = config.getProperty("broker-address", DEFAULT_BROKER_ADDRESS);
            String brokerPort = config.getProperty("broker-port", DEFAULT_BROKER_PORT);
            this.brokerBaseUrl = String.format("http://%s:%s/internal", brokerAddress, brokerPort);
        }

        // set the initial graph affinity of this worker (if it is not in the config file it will be
        // set to null, i.e. no graph affinity)
        // we don't actually build the graph now; this is just a hint to the broker as to what
        // graph this machine was intended to analyze.
        this.networkId = config.getProperty("initial-graph-id");

        this.gridCache = new GridCache(config.getProperty("aws-region"), config.getProperty("pointsets-bucket"));
        this.networkPreloader = new NetworkPreloader(transportNetworkCache);
        this.autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown", "false"));

        // Keep the worker alive for an initial window to prepare for analysis
        inPreloading = true;
        shutdownAfter = System.currentTimeMillis() + PRELOAD_KEEPALIVE_MINUTES * 60 * 1000;

        // Discover information about what EC2 instance / region we're running on, if any.
        // If the worker isn't running in Amazon EC2, then region will be unknown so fall back on a default, because
        // the new request signing v4 requires you to know the region where the S3 objects are.
        ec2info = new EC2Info();
        if (!workOffline) {
            ec2info.fetchMetadata();
        }
        if (ec2info.region == null) {
            // We're working offline and/or not running on EC2. Set a default region rather than detecting one.
            ec2info.region = Regions.EU_WEST_1.getName();
        }
    }

    /**
     * Shut down if enough time has passed since certain events (startup or handling an analysis request). When EC2
     * billing was in hourly increments, the worker would only consider shutting down every 60 minutes. But EC2
     * billing is now by the second, so we check more frequently (during regular polling).
     */
    public void considerShuttingDown() {
        long now = System.currentTimeMillis();

        if (now > shutdownAfter) {
            LOG.info("Machine has been idle for at least {} minutes (single point) and {} minutes (regional), " +
                    "shutting down.", SINGLE_KEEPALIVE_MINUTES , REGIONAL_KEEPALIVE_MINUTES);
            // Stop accepting any new single-point requests while shutdown is happening.
            // TODO maybe actively tell the broker this worker is shutting down.
            sparkHttpService.stop();
            try {
                Process process = new ProcessBuilder("sudo", "/sbin/shutdown", "-h", "now").start();
                process.waitFor();
            } catch (Exception ex) {
                LOG.error("Unable to terminate worker", ex);
                // TODO email us or something
            } finally {
                System.exit(0);
            }
        }
    }

    /**
     * This is the main worker event loop which fetches tasks from a broker and schedules them for execution.
     */
    @Override
    public void run() {

        // Create executors with up to one thread per processor.
        // The default task rejection policy is "Abort".
        // The executor's queue is rather long because some tasks complete very fast and we poll max once per second.
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        LOG.info("Java reports the number of available processors is: {}", availableProcessors);
        int maxThreads = availableProcessors;
        int taskQueueLength = availableProcessors * 6;
        LOG.info("Maximum number of regional processing threads is {}, length of task queue is {}.", maxThreads, taskQueueLength);
        BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>(taskQueueLength);
        regionalTaskExecutor = new ThreadPoolExecutor(1, maxThreads, 60, TimeUnit.SECONDS, taskQueue);

        // Before we go into an endless loop polling for regional tasks that can be computed asynchronously, start a
        // single-endpoint web server on this worker to receive single-point requests that must be handled immediately.
        // This is listening on a different port than the backend API so that a worker can be running on the backend.
        // Trying out the new Spark syntax for non-static configuration.
        // When testing task redelivery, many  workers run on the same machine. In that case, do not start this HTTP
        // server to avoid port conflicts.
        if (!testTaskRedelivery) {
            sparkHttpService = spark.Service.ignite()
                .port(WORKER_LISTEN_PORT)
                .threadPool(WORKER_SINGLE_POINT_THREADS);
            sparkHttpService.post("/single", new AnalysisWorkerController(this)::handleSinglePoint);
        }

        // Main polling loop to fill the regional work queue.
        // You'd think the ThreadPoolExecutor could just block when the blocking queue is full, but apparently
        // people all over the world have been jumping through hoops to try to achieve this simple behavior
        // with no real success, at least without writing bug and deadlock-prone custom executor services.
        // Two alternative approaches are trying to keep the queue full and waiting for the queue to be almost empty.
        // To keep the queue full, we repeatedly try to add each task to the queue, pausing and retrying when
        // it's full. To wait until it's almost empty, we could use wait() in a loop and notify() as tasks are handled.
        // see https://stackoverflow.com/a/15185004/778449
        // A simpler approach might be to spin-wait checking whether the queue is low and sleeping briefly,
        // then fetch more work only when the queue is getting empty.
        while (true) {
            List<RegionalTask> tasks = getSomeWork();
            if (tasks == null || tasks.isEmpty()) {
                // Either there was no work, or some kind of error occurred.
                // Sleep for a while before polling again, adding a random component to spread out the polling load.
                if (autoShutdown) {considerShuttingDown();}
                int randomWait = random.nextInt(POLL_MAX_RANDOM_WAIT);
                LOG.info("Polling the broker did not yield any regional tasks. Sleeping {} + {} sec.", POLL_WAIT_SECONDS, randomWait);
                sleepSeconds(POLL_WAIT_SECONDS + randomWait);
                continue;
            }
            for (RegionalTask task : tasks) {
                while (true) {
                    try {
                        // TODO define non-anonymous runnable class to instantiate here, specifically for async regional tasks.
                        regionalTaskExecutor.execute(() -> this.handleOneRegionalTask(task));
                        break;
                    } catch (RejectedExecutionException e) {
                        // Queue is full, wait a bit and try to feed it more tasks.
                        // FIXME if we burn through the internal queue in less than 1 second this is a speed bottleneck.
                        // This happens with regions unconnected to transit and with very small travel time cutoffs.
                        // FIXME this is really using the list of fetched tasks as a secondary queue, it's awkward.
                        sleepSeconds(1);
                    }
                }
            }
        }
    }

    /**
     * Bypass idiotic java checked exceptions.
     */
    public void sleepSeconds (int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Synchronously handle one single-point task.
     * @return the travel time grid (binary data) which will be passed back to the client UI. This binary response may
     *         have errors appended as JSON to the end.
     */
    protected byte[] handleOneSinglePointTask (TravelTimeSurfaceTask task)
            throws WorkerNotReadyException, ScenarioApplicationException, IOException {

        LOG.info("Handling single-point task {}", task.toString());

        // Get all the data needed to run one analysis task, or at least begin preparing it.
        final AsyncLoader.LoaderState<TransportNetwork> networkLoaderState = networkPreloader.preloadData(task);

        // If loading is not complete, bail out of this function.
        // Ideally we'd stall briefly using something like Future.get(timeout) in case loading finishes quickly.
        if (networkLoaderState.status != AsyncLoader.Status.PRESENT) {
            throw new WorkerNotReadyException(networkLoaderState);
        }

        // Get the graph object for the ID given in the task, fetching inputs and building as needed.
        // All requests handled together are for the same graph, and this call is synchronized so the graph will
        // only be built once.
        // Record the currently loaded network ID so we "stick" to this same graph on subsequent polls.
        // TODO allow for a list of multiple already loaded TransitNetworks.
        networkId = task.graphId;
        TransportNetwork transportNetwork = networkLoaderState.value;

        // After the AsyncLoader has reported all required data are ready for analysis, advance the shutdown clock to
        // reflect that the worker is performing single-point work.
        adjustShutdownClock(SINGLE_KEEPALIVE_MINUTES);

        // Perform the core travel time computations.
        TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork, gridCache);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // Prepare the travel time grid which will be written back to the client. We gzip the data before sending
        // it back to the broker. Compression ratios here are extreme (100x is not uncommon).
        // We had many "connection reset by peer" and buffer overflows errors on large files.
        // Handle gzipping with HTTP headers (caller should already be doing this)
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        // The single-origin travel time surface can be represented as a proprietary grid or as a GeoTIFF.
        if (task.getFormat() == TravelTimeSurfaceTask.Format.GEOTIFF) {
            oneOriginResult.timeGrid.writeGeotiff(byteArrayOutputStream, task);
        } else {
            // Catch-all, if the client didn't specifically ask for a GeoTIFF give it a proprietary grid.
            // Return raw byte array representing grid to caller, for return to client over HTTP.
            // TODO eventually reuse same code path as static site time grid saving
            oneOriginResult.timeGrid.writeGridToDataOutput(new LittleEndianDataOutputStream(byteArrayOutputStream));
            addErrorJson(byteArrayOutputStream, transportNetwork.scenarioApplicationWarnings);
        }
        // Single-point tasks don't have a job ID. For now, we'll categorize them by scenario ID.
        throughputTracker.recordTaskCompletion("SINGLE-" + transportNetwork.scenarioId);

        // Return raw byte array containing grid or TIFF file to caller, for return to client over HTTP.
        byteArrayOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Handle one task representing one of many origins within a regional analysis.
     * This method is generally being executed asynchronously, handling a large number of tasks on a pool of worker
     * threads. It stockpiles results as they are produced, so they can be returned to the backend in batches when the
     * worker polls the backend.
     */
    protected void handleOneRegionalTask(RegionalTask task) {

        LOG.info("Handling regional task {}", task.toString());

        // If this worker is being used in a test of the task redelivery mechanism. Report most work as completed
        // without actually doing anything, but fail to report results a certain percentage of the time.
        if (testTaskRedelivery) {
            pretendToDoWork(task);
            return;
        }

        try {
            // Having a non-null opportunity density grid in the task triggers the computation of accessibility values.
            // The gridData should not be set on static site tasks (or single-point tasks which don't even have the field).
            // Resolve the grid ID to an actual grid - this is important to determine the grid extents for the key.
            // Fetching data grids should be relatively fast so we can do it synchronously.
            // Perhaps this can be done higher up in the call stack where we know whether or not it's a regional task.
            // TODO move this after the asynchronous loading of the rest of the necessary data?
            if (!task.makeStaticSite) {
                task.gridData = gridCache.get(task.grid);
            }

            // Get the graph object for the ID given in the task, fetching inputs and building as needed.
            // All requests handled together are for the same graph, and this call is synchronized so the graph will
            // only be built once.
            // Record the currently loaded network ID so we "stick" to this same graph on subsequent polls.
            networkId = task.graphId;
            // Note we're completely bypassing the async loader here and relying on the older nested LoadingCaches.
            // If those are ever removed, the async loader will need a synchronous mode with per-key blocking (kind of
            // reinventing the wheel of LoadingCache) or we'll need to make preparation for regional tasks async.
            TransportNetwork transportNetwork = networkPreloader.transportNetworkCache.getNetworkForScenario(task
                    .graphId, task.scenarioId);

            // If we are generating a static site, there must be a single metadata file for an entire batch of results.
            // Arbitrarily we create this metadata as part of the first task in the job.
            if (task.makeStaticSite && task.taskId == 0) {
                LOG.info("This is the first task in a job that will produce a static site. Writing shared metadata.");
                saveStaticSiteMetadata(task, transportNetwork);
            }

            // Advance the shutdown clock to reflect that the worker is performing regional work.
            adjustShutdownClock(REGIONAL_KEEPALIVE_MINUTES);

            // Perform the core travel time and accessibility computations.
            TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork, gridCache);
            OneOriginResult oneOriginResult = computer.computeTravelTimes();

            if (task.makeStaticSite) {
                // Unlike a normal regional task, this will write a time grid rather than an accessibility indicator
                // value because we're generating a set of time grids for a static site. We only save a file if it has
                // non-default contents, as a way to save storage and bandwidth.
                // TODO eventually carry out actions based on what's present in the result, not on the request type.
                if (oneOriginResult.timeGrid.anyCellReached()) {
                    PersistenceBuffer persistenceBuffer = oneOriginResult.timeGrid.writeToPersistenceBuffer();
                    String timesFileName = task.taskId + "_times.dat";
                    filePersistence.saveStaticSiteData(task, timesFileName, persistenceBuffer);
                } else {
                    LOG.info("No destination cells reached. Not saving static site file to reduce storage space.");
                }
            }

            // Accumulate accessibility results, which will be returned to the backend in batches.
            // For most regional analyses, this is an accessibility indicator value for one of many origins,
            // but for static sites the indicator value is not known, it is computed in the UI. We still want to return
            // dummy (zero) accessibility results so the backend is aware of progress through the list of origins.
            synchronized (workResults) {
                workResults.add(oneOriginResult.toRegionalWorkResult(task));
            }
            throughputTracker.recordTaskCompletion(task.jobId);
        } catch (Exception ex) {
            LOG.error("An error occurred while handling a regional task: {}", ExceptionUtils.asString(ex));
            // TODO communicate regional analysis errors to the backend (in workResults)
        }
    }

    /**
     * Used in tests of the task redelivery mechanism. Report work as completed without actually doing anything,
     * but fail to report results a certain percentage of the time.
     */
    private void pretendToDoWork (RegionalTask task) {
        try {
            // Pretend the task takes 1-2 seconds to complete.
            Thread.sleep(random.nextInt(1000) + 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (random.nextInt(100) >= TESTING_FAILURE_RATE_PERCENT) {
            RegionalWorkResult workResult = new RegionalWorkResult(task.jobId, task.taskId, 1, 1, 1);
            synchronized (workResults) {
                workResults.add(workResult);
            }
        } else {
            LOG.info("Intentionally failing to complete task {} for testing purposes.", task.taskId);
        }
    }

    /**
     * This is somewhat hackish - when we want to return errors to the UI, we just append them as JSON at the end of
     * a binary result. We always append this JSON even when there are no errors so the UI has something to decode,
     * even if it's an empty list.
     *
     * TODO use different HTTP codes and MIME types to return errors or valid results.
     * We probably want to keep doing this though because we want to return a result AND the warnings.
     */
    public static void addErrorJson (OutputStream outputStream, List<TaskError> scenarioApplicationWarnings) throws IOException {
        LOG.info("Travel time surface written, appending metadata with scenario application {} warnings", scenarioApplicationWarnings.size());
        // We create a single-entry map because this converts easily to a JSON object.
        Map<String, List<TaskError>> errorsToSerialize = new HashMap<>();
        errorsToSerialize.put("scenarioApplicationWarnings", scenarioApplicationWarnings);
        // We could do this when setting up the Spark handler, supplying writeValue as the response transformer
        // But then you also have to handle the case where you are returning raw bytes.
        JsonUtilities.objectMapper.writeValue(outputStream, errorsToSerialize);
        LOG.info("Done writing");
    }

    /**
     * Ask the backend if it has any work for this worker, considering its software version and loaded networks.
     * Also report the worker status to the backend, serving as a heartbeat so the backend knows this worker is alive.
     * Also returns any accumulated work results to the backend.
     * @return a list of work tasks, or null if there was no work to do, or if no work could be fetched.
     */
    public List<RegionalTask> getSomeWork () {
        String url = brokerBaseUrl + "/poll";
        HttpPost httpPost = new HttpPost(url);
        WorkerStatus workerStatus = new WorkerStatus(this);
        // Include all completed work results when polling the backend.
        // Atomically copy and clear the accumulated work results, while blocking writes from other threads.
        synchronized (workResults) {
            workerStatus.results = new ArrayList<>(workResults);
            workResults.clear();
        }

        // Compute throughput in tasks per minute and include it in the worker status report.
        // We poll too frequently to compute throughput just since the last poll operation.
        // TODO reduce polling frequency (larger queue in worker), compute shorter-term throughput.
        workerStatus.tasksPerMinuteByJobId = throughputTracker.getTasksPerMinuteByJobId();

        // Report how often we're polling for work, just for monitoring.
        long timeNow = System.currentTimeMillis();
        workerStatus.secondsSinceLastPoll = (timeNow - lastPollingTime) / 1000D;
        lastPollingTime = timeNow;

        httpPost.setEntity(JsonUtilities.objectToJsonHttpEntity(workerStatus));
        HttpEntity responseEntity = null;
        try {
            HttpResponse response = httpClient.execute(httpPost);
            responseEntity = response.getEntity();
            if (response.getStatusLine().getStatusCode() == 204) {
                // Broker said there's no work to do.
                return null;
            }
            if (response.getStatusLine().getStatusCode() == 200 && responseEntity != null) {
                // Broker returned some work. Use the lenient object mapper to decode it in case the broker is a
                // newer version so sending unrecognizable fields.
                // ReadValue closes the stream, releasing the HTTP connection.
                return JsonUtilities.lenientObjectMapper.readValue(responseEntity.getContent(),
                        new TypeReference<List<RegionalTask>>() {});
            }
            // Non-200 response code or a null entity. Something is weird.
            LOG.error("Unsuccessful polling. HTTP response code: " + response.getStatusLine().getStatusCode());
        } catch (Exception e) {
            LOG.error("Exception while polling backend for work: {}",ExceptionUtils.asString(e));
        } finally {
            // We have to properly close any streams so the HTTP connection is released back to the (finite) pool.
            EntityUtils.consumeQuietly(responseEntity);
        }
        // If we did not return yet, something went wrong and the results were not delivered. Put them back on the list
        // for later re-delivery, safely interleaving with new results that may be coming from other worker threads.
        synchronized (workResults) {
            // TODO check here that results are not piling up too much?
            workResults.addAll(workerStatus.results);
        }
        return null;
    }

    /**
     * Generate and write out metadata describing what's in a directory of static site output.
     */
    public static void saveStaticSiteMetadata (AnalysisTask analysisTask, TransportNetwork network) {
        try {
            // Save the regional analysis request, giving the UI some context to display the results.
            // This is the request object sent to the workers to generate these static site regional results.
            PersistenceBuffer buffer = PersistenceBuffer.serializeAsJson(analysisTask);
            AnalystWorker.filePersistence.saveStaticSiteData(analysisTask, "request.json", buffer);

            // Save non-fatal warnings encountered applying the scenario to the network for this regional analysis.
            buffer = PersistenceBuffer.serializeAsJson(network.scenarioApplicationWarnings);
            AnalystWorker.filePersistence.saveStaticSiteData(analysisTask, "warnings.json", buffer);

            // Save transit route data that allows rendering paths with the Transitive library in a separate file.
            TransitiveNetwork transitiveNetwork = new TransitiveNetwork(network.transitLayer);
            buffer = PersistenceBuffer.serializeAsJson(transitiveNetwork);
            AnalystWorker.filePersistence.saveStaticSiteData(analysisTask, "transitive.json", buffer);
        } catch (Exception e) {
            LOG.error("Exception saving static metadata: {}", ExceptionUtils.asString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * Requires a worker configuration, which is a Java Properties file with the following
     * attributes.
     *
     * graphs-bucket      S3 bucket in which graphs are stored.
     * pointsets-bucket   S3 bucket in which pointsets are stored
     * auto-shutdown      Should this worker shut down its machine if it is idle (e.g. on throwaway cloud instances)
     * initial-graph-id   The graph ID for this worker to load immediately upon startup
     */
    public static void main (String[] args) {
        LOG.info("Starting R5 Analyst Worker version {}", R5Version.version);
        LOG.info("R5 commit is {}", R5Version.commit);
        LOG.info("R5 describe is {}", R5Version.describe);

        String configFileName = "worker.conf";
        if (args.length > 0) {
            configFileName = args[0];
        }
        Properties config = new Properties();
        try (InputStream configInputStream = new FileInputStream(new File(configFileName))) {
            config.load(configInputStream);
        } catch (Exception e) {
            LOG.error("Error loading worker configuration, shutting down. " + ExceptionUtils.asString(e));
            return;
        }
        try {
            AnalystWorker.forConfig(config).run();
        } catch (Exception e) {
            LOG.error("Unhandled error in analyst worker, shutting down. " + ExceptionUtils.asString(e));
        }
    }

}
