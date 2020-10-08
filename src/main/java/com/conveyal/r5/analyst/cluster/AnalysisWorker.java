package com.conveyal.r5.analyst.cluster;

import com.amazonaws.regions.Regions;
import com.conveyal.analysis.BackendVersion;
import com.conveyal.file.FileStorage;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.file.S3FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.AccessibilityResult;
import com.conveyal.r5.analyst.FilePersistence;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.S3FilePersistence;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.streets.OSMCache;
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
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.profile.PerTargetPropagater.SECONDS_PER_MINUTE;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * This is a main class run by worker machines in our Analysis computation cluster.
 * It polls a broker requesting work over HTTP, telling the broker what networks and scenarios it has loaded.
 * When it receives some work from the broker it does the necessary work and returns the results back to the front
 * end via the broker.
 *
 * The worker can poll for work over two different channels. One is for large asynchronous batch jobs, the other is
 * intended for interactive single point requests that should return as fast as possible.
 */
public class AnalysisWorker implements Runnable {

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

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    private static final String DEFAULT_BROKER_ADDRESS = "localhost";

    private static final String DEFAULT_BROKER_PORT = "7070";

    public static final int POLL_WAIT_SECONDS = 15;

    public static final int POLL_MAX_RANDOM_WAIT = 5;

    /** The port on which the worker will listen for single point tasks forwarded from the backend. */
    public static final int WORKER_LISTEN_PORT = 7080;

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
    static final int REGIONAL_KEEPALIVE_MINUTES = 5;
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
     * Preparing networks or linking grids will take longer, but those cases are now handled with
     * WorkerNotReadyException.
     */
    private static final int HTTP_CLIENT_TIMEOUT_SEC = 55;

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
     * This worker will only listen for incoming single point requests if this field is true when run() is invoked.
     * Setting this to false before running creates a regional-only cluster worker. This is useful in testing when
     * running many workers on the same machine.
     */
    protected boolean listenForSinglePointRequests;

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
    PointSetCache pointSetCache;

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

    public static AnalysisWorker forConfig (Properties config) {
        // FIXME why is there a separate configuration parsing section here? Why not always make the cache based on the configuration?
        // FIXME why is some configuration done here and some in the constructor?
        boolean workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        String graphDirectory = config.getProperty("cache-dir", "cache/graphs");
        FileStorage fileStore;
        if (workOffline) {
            fileStore = new LocalFileStorage(graphDirectory);
        } else {
            fileStore = new S3FileStorage(config.getProperty("aws-region"), graphDirectory);
        }

        // TODO worker config classes structured like BackendConfig
        String graphsBucket = workOffline ? null : config.getProperty("graphs-bucket");
        OSMCache osmCache = new OSMCache(fileStore, () -> graphsBucket);
        GTFSCache gtfsCache = new GTFSCache(fileStore, () -> graphsBucket);

        TransportNetworkCache cache = new TransportNetworkCache(fileStore, gtfsCache, osmCache, graphsBucket);
        return new AnalysisWorker(config, fileStore, cache);
    }

    // TODO merge this constructor with the forConfig factory method, so we don't have different logic for local and cluster workers
    public AnalysisWorker (Properties config, FileStorage fileStore, TransportNetworkCache transportNetworkCache) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker {} starting at {}", machineId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        // PARSE THE CONFIGURATION TODO move configuration parsing into a separate method.

        testTaskRedelivery = Boolean.parseBoolean(config.getProperty("test-task-redelivery", "false"));

        // Region region = Region.getRegion(Regions.fromName(config.getProperty("aws-region")));
        // TODO Eliminate this default base-bucket value "analysis-staging" and set it properly when the backend starts workers.
        //      It's currently harmless to hard-wire it because it only affects polygon downloads for experimental modifications.
        filePersistence = new S3FilePersistence(config.getProperty("aws-region"), config.getProperty("base-bucket", "analysis-staging"));

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

        this.pointSetCache = new PointSetCache(fileStore, config.getProperty("pointsets-bucket"));
        this.networkPreloader = new NetworkPreloader(transportNetworkCache);
        this.autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown", "false"));
        this.listenForSinglePointRequests = Boolean.parseBoolean(config.getProperty("listen-for-single-point", "true"));

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
        // When testing cluster functionality, e.g. task redelivery, many  workers run on the same machine. In that
        // case, this HTTP server is disabled on all workers but one to avoid port conflicts.
        // Ideally we would limit the number of threads the worker will use to handle HTTP connections, in order to
        // crudely limit memory consumption and load from simultaneous single point requests. Unfortunately we can't
        // call sparkHttpService.threadPool(NTHREADS) because we get an error message saying we need over 10 threads:
        // "needed(acceptors=1 + selectors=8 + request=1)". Even worse, in container-based testing environments this
        // required number of threads is even higher and any value we specify can cause the server (and tests) to fail.
        // TODO find a more effective way to limit simultaneous computations, e.g. feed them through the regional thread pool.
        if (listenForSinglePointRequests) {
            // Use the newer non-static Spark framework syntax.
            sparkHttpService = spark.Service.ignite().port(WORKER_LISTEN_PORT);
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
    public static void sleepSeconds (int seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Synchronously handle one single-point task.
     * @return the travel time grid (binary data) which will be passed back to the client UI, with a JSON block at the
     *         end containing accessibility figures, scenario application warnings, and informational messages.
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

        // The presence of destination point set keys indicates that we should calculate single-point accessibility.
        // Every task should include a decay function (set to step function by backend if not supplied by user).
        // In this case our highest cutoff is always 120, so we need to search all the way out to 120 minutes.
        if (notNullOrEmpty(task.destinationPointSetKeys)) {
            task.decayFunction.prepare();
            task.cutoffsMinutes = IntStream.rangeClosed(0, 120).toArray();
            task.maxTripDurationMinutes = 120;
            task.loadAndValidateDestinationPointSets(pointSetCache);
        }

        // After the AsyncLoader has reported all required data are ready for analysis, advance the shutdown clock to
        // reflect that the worker is performing single-point work.
        adjustShutdownClock(SINGLE_KEEPALIVE_MINUTES);

        // Perform the core travel time computations.
        TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // Prepare the travel time grid which will be written back to the client. We gzip the data before sending
        // it back to the broker. Compression ratios here are extreme (100x is not uncommon).
        // We had many "connection reset by peer" and buffer overflows errors on large files.
        // Handle gzipping with HTTP headers (caller should already be doing this)
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        // The single-origin travel time surface can be represented as a proprietary grid or as a GeoTIFF.
        TimeGridWriter timeGridWriter = new TimeGridWriter(oneOriginResult.travelTimes, task);
        if (task.getFormat() == TravelTimeSurfaceTask.Format.GEOTIFF) {
            timeGridWriter.writeGeotiff(byteArrayOutputStream);
        } else {
            // Catch-all, if the client didn't specifically ask for a GeoTIFF give it a proprietary grid.
            // Return raw byte array representing grid to caller, for return to client over HTTP.
            // TODO eventually reuse same code path as static site time grid saving
            // TODO move the JSON writing code into the grid writer, it's essentially part of the grid format
            timeGridWriter.writeToDataOutput(new LittleEndianDataOutputStream(byteArrayOutputStream));
            addJsonToGrid(
                    byteArrayOutputStream,
                    oneOriginResult.accessibility,
                    transportNetwork.scenarioApplicationWarnings,
                    transportNetwork.scenarioApplicationInfo
            );
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

        // Ensure we don't try to calculate accessibility to missing opportunity data points.
        // This is a worker-side temporary stopgap until our new backend version is rolled out.
        if (task.makeTauiSite) {
            task.recordAccessibility = false;
        }

        // Bump the max trip duration up to find opportunities past the cutoff when using wide decay functions.
        // Save the existing hard-cutoff value which is used when saving travel times.
        // TODO this needs to happen for both regional and single point tasks when calculating accessibility on the worker
        {
            task.decayFunction.prepare();
            int maxCutoffMinutes = Arrays.stream(task.cutoffsMinutes).max().getAsInt();
            int maxTripDurationSeconds = task.decayFunction.reachesZeroAt(maxCutoffMinutes * SECONDS_PER_MINUTE);
            int maxTripDurationMinutes = (int)(Math.ceil(maxTripDurationSeconds / 60D));
            checkState(maxTripDurationMinutes <= 120, "Distance decay function must reach zero at or before 120 minutes.");
            task.maxTripDurationMinutes = maxTripDurationMinutes;
            LOG.info("Maximum cutoff was {} minutes, limiting trip duration to {} minutes based on decay function {}.",
                    maxCutoffMinutes, maxTripDurationMinutes, task.decayFunction.getClass().getSimpleName());
        }

        try {
            // TODO (re)validate multi-percentile and multi-cutoff parameters. Validation currently in TravelTimeReducer.
            //  This version should require both arrays to be present, and single values to be missing.
            // Using a newer backend, the task should have been normalized to use arrays not single values.
            checkNotNull(task.cutoffsMinutes, "This worker requires an array of cutoffs (rather than a single value).");
            checkNotNull(task.percentiles, "This worker requires an array of percentiles (rather than a single one).");
            checkElementIndex(0, task.cutoffsMinutes.length, "Regional task must specify at least one cutoff.");
            checkElementIndex(0, task.percentiles.length, "Regional task must specify at least one percentile.");

            // Get the graph object for the ID given in the task, fetching inputs and building as needed.
            // All requests handled together are for the same graph, and this call is synchronized so the graph will
            // only be built once.
            // Record the currently loaded network ID so we "stick" to this same graph on subsequent polls.
            networkId = task.graphId;
            // Note we're completely bypassing the async loader here and relying on the older nested LoadingCaches.
            // If those are ever removed, the async loader will need a synchronous mode with per-path blocking (kind of
            // reinventing the wheel of LoadingCache) or we'll need to make preparation for regional tasks async.
            TransportNetwork transportNetwork = networkPreloader.transportNetworkCache.getNetworkForScenario(task
                    .graphId, task.scenarioId);

            // Static site tasks do not specify destinations, but all other regional tasks should.
            // Load the PointSets based on the IDs (actually, full storage keys including IDs) in the task.
            // The presence of these grids in the task will then trigger the computation of accessibility values.
            if (!task.makeTauiSite) {
                task.loadAndValidateDestinationPointSets(pointSetCache);
            }

            // If we are generating a static site, there must be a single metadata file for an entire batch of results.
            // Arbitrarily we create this metadata as part of the first task in the job.
            if (task.makeTauiSite && task.taskId == 0) {
                LOG.info("This is the first task in a job that will produce a static site. Writing shared metadata.");
                saveStaticSiteMetadata(task, transportNetwork);
            }

            // Advance the shutdown clock to reflect that the worker is performing regional work.
            adjustShutdownClock(REGIONAL_KEEPALIVE_MINUTES);

            // Perform the core travel time and accessibility computations.
            TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork);
            OneOriginResult oneOriginResult = computer.computeTravelTimes();

            if (task.makeTauiSite) {
                // Unlike a normal regional task, this will write a time grid rather than an accessibility indicator
                // value because we're generating a set of time grids for a static site. We only save a file if it has
                // non-default contents, as a way to save storage and bandwidth.
                // TODO eventually carry out actions based on what's present in the result, not on the request type.
                if (oneOriginResult.travelTimes.anyCellReached()) {
                    TimeGridWriter timeGridWriter = new TimeGridWriter(oneOriginResult.travelTimes, task);
                    PersistenceBuffer persistenceBuffer = timeGridWriter.writeToPersistenceBuffer();
                    String timesFileName = task.taskId + "_times.dat";
                    filePersistence.saveStaticSiteData(task, timesFileName, persistenceBuffer);
                } else {
                    LOG.info("No destination cells reached. Not saving static site file to reduce storage space.");
                }
                // Overwrite with an empty set of results to send back to the backend, allowing it to track job
                // progress. This avoids crashing the backend by sending back massive 2 million element travel times
                // that have already been written to S3, and throwing exceptions on old backends that can't deal with
                // null AccessibilityResults.
                oneOriginResult = new OneOriginResult(null, new AccessibilityResult(task));
            }

            // Accumulate accessibility results, which will be returned to the backend in batches.
            // For most regional analyses, this is an accessibility indicator value for one of many origins,
            // but for static sites the indicator value is not known, it is computed in the UI. We still want to return
            // dummy (zero) accessibility results so the backend is aware of progress through the list of origins.
            synchronized (workResults) {
                workResults.add(new RegionalWorkResult(oneOriginResult, task));
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
            OneOriginResult emptyContainer = new OneOriginResult(null, new AccessibilityResult());
            synchronized (workResults) {
                workResults.add(new RegionalWorkResult(emptyContainer, task));
            }
        } else {
            LOG.info("Intentionally failing to complete task {} for testing purposes.", task.taskId);
        }
    }

    /**
     * This is a model from which we can serialize the block of JSON metadata at the end of a
     * binary grid of travel times, which we return from the worker to the UI via the backend.
     */
    public static class GridJsonBlock {

        /**
         * For each destination pointset, for each percentile, for each cutoff minute from zero to 120 (inclusive), the
         * cumulative opportunities accessibility including effects of the distance decay function. We may eventually
         * want to also include the marginal opportunities at each minute, without the decay function applied.
         */
        public int[][][] accessibility;

        public List<TaskError> scenarioApplicationWarnings;

        public List<TaskError> scenarioApplicationInfo;

        @Override
        public String toString () {
            return String.format(
                "[travel time grid metadata block with %d warning and %d informational messages]",
                scenarioApplicationWarnings.size(),
                scenarioApplicationInfo.size()
            );
        }
    }

    /**
     * Our binary travel time grid format ends with a block of JSON containing additional structured data.
     * This includes
     * This is somewhat hackish - when we want to return errors to the UI, we just append them as JSON at the end of
     * We always append this JSON even when it won't contain anything so the UI has something to decode.
     * The response's HTTP status code is set by the caller - it may be an error or not. If we only have warnings
     * and no serious errors, we use a success error code.
     * TODO distinguish between warnings and errors - we already distinguish between info and warnings.
     * This could be turned into a GridJsonBlock constructor, with the JSON writing code in an instance method.
     */
    public static void addJsonToGrid (
            OutputStream outputStream,
            AccessibilityResult accessibilityResult,
            List<TaskError> scenarioApplicationWarnings,
            List<TaskError> scenarioApplicationInfo
    ) throws IOException {
        var jsonBlock = new GridJsonBlock();
        jsonBlock.scenarioApplicationInfo = scenarioApplicationInfo;
        jsonBlock.scenarioApplicationWarnings = scenarioApplicationWarnings;
        if (accessibilityResult != null) {
            // Due to the application of distance decay functions, we may want to make the shift to non-integer
            // accessibility values (especially for cases where there are relatively few opportunities across the whole
            // study area). But we'd need to control the number of decimal places serialized into the JSON.
            jsonBlock.accessibility = accessibilityResult.getIntValues();
        }
        LOG.info("Travel time surface written, appending {}.", jsonBlock);
        // We could do this when setting up the Spark handler, supplying writeValue as the response transformer
        // But then you also have to handle the case where you are returning raw bytes.
        JsonUtilities.objectMapper.writeValue(outputStream, jsonBlock);
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
    public static void saveStaticSiteMetadata (AnalysisWorkerTask analysisWorkerTask, TransportNetwork network) {
        try {
            // Save the regional analysis request, giving the UI some context to display the results.
            // This is the request object sent to the workers to generate these static site regional results.
            PersistenceBuffer buffer = PersistenceBuffer.serializeAsJson(analysisWorkerTask);
            AnalysisWorker.filePersistence.saveStaticSiteData(analysisWorkerTask, "request.json", buffer);

            // Save non-fatal warnings encountered applying the scenario to the network for this regional analysis.
            buffer = PersistenceBuffer.serializeAsJson(network.scenarioApplicationWarnings);
            AnalysisWorker.filePersistence.saveStaticSiteData(analysisWorkerTask, "warnings.json", buffer);

            // Save transit route data that allows rendering paths with the Transitive library in a separate file.
            TransitiveNetwork transitiveNetwork = new TransitiveNetwork(network.transitLayer);
            buffer = PersistenceBuffer.serializeAsJson(transitiveNetwork);
            AnalysisWorker.filePersistence.saveStaticSiteData(analysisWorkerTask, "transitive.json", buffer);
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
        LOG.info("Starting R5 Analyst Worker version {}", BackendVersion.instance.version);
        LOG.info("R5 git commit is {}", BackendVersion.instance.commit);
        LOG.info("R5 git branch is {}", BackendVersion.instance.branch);

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
            AnalysisWorker.forConfig(config).run();
        } catch (Exception e) {
            LOG.error("Unhandled error in analyst worker, shutting down. " + ExceptionUtils.asString(e));
        }
    }

}
