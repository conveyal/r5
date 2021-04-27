package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.HandleRegionalEvent;
import com.conveyal.analysis.components.eventbus.HandleSinglePointEvent;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.AccessibilityResult;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
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
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask.Format.GEOTIFF;
import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.profile.PerTargetPropagater.SECONDS_PER_MINUTE;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This is a main class run by worker machines in our Analysis computation cluster. It polls a broker requesting work
 * over HTTP, telling the broker what networks and scenarios it has loaded. When it receives some work from the broker
 * it does the necessary work and returns the results back to the front end via the broker.
 * The worker may also listen for interactive single point requests that should return as fast as possible.
 */
public class AnalysisWorker implements Runnable {

    /**
     * All parameters needed to configure an AnalysisWorker instance.
     * This config interface is kind of huge and includes most things in the WorkerConfig.
     * This implies too much functionality is concentrated in AnalysisWorker and should be compartmentalized.
     */
    public interface Config {

        /**
         * This worker will only listen for incoming single point requests if this field is true when run() is invoked.
         * Setting this to false before running creates a regional-only cluster worker.
         * This is useful in testing when running many workers on the same machine.
         */
        boolean listenForSinglePoint();

        /**
         * If this is true, the worker will not actually do any work. It will just report all tasks as completed
         * after a small delay, but will fail to do so on the given percentage of tasks. This is used in testing task
         * re-delivery and overall broker sanity with multiple jobs and multiple failing workers.
         */
        boolean testTaskRedelivery();
        String brokerAddress();
        String brokerPort();
        String initialGraphId();

    }

    // CONSTANTS

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    public static final int POLL_WAIT_SECONDS = 15;
    public static final int POLL_MAX_RANDOM_WAIT = 5;

    /**
     * This timeout should be longer than the longest expected worker calculation for a single-point request.
     * Preparing networks or linking grids will take longer, but those cases are now handled with
     * WorkerNotReadyException.
     */
    private static final int HTTP_CLIENT_TIMEOUT_SEC = 55;

    /** The port on which the worker will listen for single point tasks forwarded from the backend. */
    public static final int WORKER_LISTEN_PORT = 7080;

    /**
     * When testTaskRedelivery=true, how often the worker will fail to return a result for a task.
     * TODO merge this with the boolean config parameter to enable intentional failure.
     */
    public static final int TESTING_FAILURE_RATE_PERCENT = 20;


    // STATIC FIELDS

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

    // INSTANCE FIELDS

    /** Hold a reference to the config object to avoid copying the many config values. */
    private final Config config;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    public final NetworkPreloader networkPreloader;

    private final Random random = new Random();

    /** The common root of all API URLs contacted by this worker, e.g. http://localhost:7070/api/ */
    protected final String brokerBaseUrl;

    /** The HTTP client the worker uses to contact the broker and fetch regional analysis tasks. */
    private final HttpClient httpClient = makeHttpClient();

    /**
     * The results of finished work accumulate here, and will be sent in batches back to the broker.
     * All access to this field should be synchronized since it will is written to by multiple threads.
     * We don't want to just wrap it in a SynchronizedList because we need an atomic copy-and-empty operation.
     */
    private final List<RegionalWorkResult> workResults = new ArrayList<>();

    /** The last time (in milliseconds since the epoch) that we polled for work. */
    private long lastPollingTime;

    /** Keep track of how many tasks per minute this worker is processing, broken down by scenario ID. */
    private final ThroughputTracker throughputTracker = new ThroughputTracker();

    /** Convenience method allowing the backend broker and the worker to make similar HTTP clients. */
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

    private final FileStorage fileStorage;

    /**
     * A loading cache of opportunity dataset grids (not grid pointsets or linkages).
     * TODO use the WebMercatorGridExtents in these Grids.
     */
    private final PointSetCache pointSetCache;

    /** Information about the EC2 instance (if any) this worker is running on. */
    public EC2Info ec2info;

    /** The transport network this worker already has loaded, and therefore prefers to work on. */
    protected String networkId = null;

    /**
     * A queue to hold a backlog of regional analysis tasks.
     * This avoids "slow joiner" syndrome where we wait to poll for more work until all N fetched tasks have finished,
     * but one of the tasks takes much longer than all the rest.
     * This should be long enough to hold all that have come in - we don't need to block on polling the manager.
     * Can this be replaced with the general purpose TaskScheduler component?
     * That will depend whether all TaskScheduler Tasks are tracked in a way intended to be visible to users.
     */
    private ThreadPoolExecutor regionalTaskExecutor;

    /** The HTTP server that receives single-point requests. TODO make this more consistent with the backend HTTP API components. */
    private spark.Service sparkHttpService;

    private final EventBus eventBus;

    /** Constructor that takes injected components. */
    public AnalysisWorker (
            FileStorage fileStorage,
            TransportNetworkCache transportNetworkCache,
            EventBus eventBus,
            Config config
    ) {
        // Print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker {} starting at {}", machineId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        this.config = config;
        this.brokerBaseUrl = String.format("http://%s:%s/internal", config.brokerAddress(), config.brokerPort());

        // Set the initial graph affinity of this worker (which will be null in local operation).
        // We don't actually build / load / process the TransportNetwork until we receive the first task.
        // This just provides a hint to the broker as to what network this machine was intended to analyze.
        this.networkId = config.initialGraphId();
        this.fileStorage = fileStorage;
        this.pointSetCache = new PointSetCache(fileStorage); // Make this cache a component?
        this.networkPreloader = new NetworkPreloader(transportNetworkCache);
        this.eventBus = eventBus;
    }

    /** The main worker event loop which fetches tasks from a broker and schedules them for execution. */
    @Override
    public void run() {

        // Create executors with up to one thread per processor.
        // The default task rejection policy is "Abort".
        // The executor's queue is rather long because some tasks complete very fast and we poll max once per second.
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        LOG.debug("Java reports the number of available processors is: {}", availableProcessors);
        int maxThreads = availableProcessors;
        int taskQueueLength = availableProcessors * 6;
        LOG.debug("Maximum number of regional processing threads is {}, length of task queue is {}.", maxThreads, taskQueueLength);
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
        if (config.listenForSinglePoint()) {
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
                // TODO only randomize delay on the first round, after that it's excessive.
                int randomWait = random.nextInt(POLL_MAX_RANDOM_WAIT);
                LOG.debug("Polling the broker did not yield any regional tasks. Sleeping {} + {} sec.", POLL_WAIT_SECONDS, randomWait);
                sleepSeconds(POLL_WAIT_SECONDS + randomWait);
                continue;
            }
            for (RegionalTask task : tasks) {
                // Try to enqueue each task for execution, repeatedly failing until the queue is not full.
                // The list of fetched tasks essentially serves as a secondary queue, which is awkward. This is using
                // exceptions for normal flow control, which is nasty. We should do this differently (#596).
                while (true) {
                    try {
                        // TODO define non-anonymous runnable class to instantiate here, specifically for async regional tasks.
                        regionalTaskExecutor.execute(() -> {
                            try {
                                this.handleOneRegionalTask(task);
                            } catch (Throwable t) {
                                LOG.error(
                                    "An error occurred while handling a regional task, reporting to backend. {}",
                                    ExceptionUtils.stackTraceString(t)
                                );
                                synchronized (workResults) {
                                    workResults.add(new RegionalWorkResult(t, task));
                                }
                            }
                        });
                        break;
                    } catch (RejectedExecutionException e) {
                        // Queue is full, wait a bit and try to feed it more tasks. If worker handles all tasks in its
                        // internal queue in less than 1 second, this is a speed bottleneck. This happens with regions
                        // unconnected to transit and with very small travel time cutoffs.
                        sleepSeconds(1);
                    }
                }
            }
        }
    }

    /** Bypass idiotic java checked exceptions. */
    public static void sleepSeconds (int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected byte[] handleAndSerializeOneSinglePointTask (TravelTimeSurfaceTask task) throws IOException {
        LOG.debug("Handling single-point task {}", task.toString());
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
        OneOriginResult oneOriginResult = handleOneSinglePointTask(task, transportNetwork);
        return singlePointResultToBinary(oneOriginResult, task, transportNetwork);
    }

    /**
     * Synchronously handle one single-point task.
     * @return the travel time grid (binary data) which will be passed back to the client UI, with a JSON block at the
     *         end containing accessibility figures, scenario application warnings, and informational messages.
     */
    protected OneOriginResult handleOneSinglePointTask (TravelTimeSurfaceTask task, TransportNetwork transportNetwork) {

        // The presence of destination point set keys indicates that we should calculate single-point accessibility.
        // Every task should include a decay function (set to step function by backend if not supplied by user).
        // In this case our highest cutoff is always 120, so we need to search all the way out to 120 minutes.
        if (notNullOrEmpty(task.destinationPointSetKeys)) {
            task.decayFunction.prepare();
            task.cutoffsMinutes = IntStream.rangeClosed(0, 120).toArray();
            task.maxTripDurationMinutes = 120;
            task.loadAndValidateDestinationPointSets(pointSetCache);
        }

        // After the AsyncLoader has reported all required data are ready for analysis,
        // signal that we will begin processing the task.
        eventBus.send(new HandleSinglePointEvent());

        // Perform the core travel time computations.
        TravelTimeComputer computer = new TravelTimeComputer(task, transportNetwork);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();
        return oneOriginResult;
    }

    private byte[] singlePointResultToBinary (
            OneOriginResult oneOriginResult,
            TravelTimeSurfaceTask task,
            TransportNetwork transportNetwork
    ) throws IOException {
        // Prepare a binary travel time grid which will be sent back to the client via the backend (broker). Data are
        // gzipped before sending back to the broker. Compression ratios here are extreme (100x is not uncommon).
        // We had many "connection reset by peer" and buffer overflow errors on large files.
        // Handle gzipping with HTTP headers (caller should already be doing this)
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        // The single-origin travel time surface can be represented as a proprietary grid or as a GeoTIFF.
        TimeGridWriter timeGridWriter = new TimeGridWriter(oneOriginResult.travelTimes, task);
        if (task.getFormat() == GEOTIFF) {
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
                    transportNetwork.scenarioApplicationInfo,
                    oneOriginResult.paths
            );
        }
        // Single-point tasks don't have a job ID. For now, we'll categorize them by scenario ID.
        this.throughputTracker.recordTaskCompletion("SINGLE-" + transportNetwork.scenarioId);

        // Return raw byte array containing grid or TIFF file to caller, for return to client over HTTP.
        byteArrayOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Handle one task representing one of many origins within a regional analysis.
     * This method is generally being executed asynchronously, handling a large number of tasks on a pool of worker
     * threads. It stockpiles results as they are produced, so they can be returned to the backend in batches when the
     * worker polls the backend. If any problem is encountered, the Throwable may be allowed to propagate up as all
     * Throwables will be caught and reported to the backend, causing the regional job to end.
     */
    protected void handleOneRegionalTask (RegionalTask task) throws Throwable {

        LOG.debug("Handling regional task {}", task.toString());

        // If this worker is being used in a test of the task redelivery mechanism. Report most work as completed
        // without actually doing anything, but fail to report results a certain percentage of the time.
        if (config.testTaskRedelivery()) {
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
            if (maxTripDurationMinutes > 120) {
                LOG.warn("Distance decay function reached zero above 120 minutes. Capping travel time at 120 minutes.");
                maxTripDurationMinutes = 120;
            }
            task.maxTripDurationMinutes = maxTripDurationMinutes;
            LOG.debug("Maximum cutoff was {} minutes, limiting trip duration to {} minutes based on decay function {}.",
                    maxCutoffMinutes, maxTripDurationMinutes, task.decayFunction.getClass().getSimpleName());
        }

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
            saveTauiMetadata(task, transportNetwork);
        }

        // After the TransportNetwork has been loaded, signal that we will begin processing the task.
        eventBus.send(new HandleRegionalEvent());

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
                fileStorage.saveTauiData(task, timesFileName, persistenceBuffer);
            } else {
                LOG.debug("No destination cells reached. Not saving static site file to reduce storage space.");
            }
            // Overwrite with an empty set of results to send back to the backend, allowing it to track job
            // progress. This avoids crashing the backend by sending back massive 2 million element travel times
            // that have already been written to S3, and throwing exceptions on old backends that can't deal with
            // null AccessibilityResults.
            oneOriginResult = new OneOriginResult(null, new AccessibilityResult(task), null);
        }

        // Accumulate accessibility results, which will be returned to the backend in batches.
        // For most regional analyses, this is an accessibility indicator value for one of many origins,
        // but for static sites the indicator value is not known, it is computed in the UI. We still want to return
        // dummy (zero) accessibility results so the backend is aware of progress through the list of origins.
        synchronized (workResults) {
            workResults.add(new RegionalWorkResult(oneOriginResult, task));
        }
        throughputTracker.recordTaskCompletion(task.jobId);
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
            OneOriginResult emptyContainer = new OneOriginResult(null, new AccessibilityResult(), null);
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

        public List<PathResult.PathIterations> pathSummaries;

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
            List<TaskError> scenarioApplicationInfo,
            PathResult pathResult
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
        jsonBlock.pathSummaries = pathResult == null ? Collections.EMPTY_LIST : pathResult.getPathIterationsForDestination();
        LOG.debug("Travel time surface written, appending {}.", jsonBlock);
        // We could do this when setting up the Spark handler, supplying writeValue as the response transformer
        // But then you also have to handle the case where you are returning raw bytes.
        JsonUtilities.objectMapper.writeValue(outputStream, jsonBlock);
        LOG.debug("Done writing");
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
            LOG.error("Exception while polling backend for work: {}",ExceptionUtils.stackTraceString(e));
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
    public void saveTauiMetadata (AnalysisWorkerTask analysisWorkerTask, TransportNetwork network) {
        try {
            // Save the regional analysis request, giving the UI some context to display the results.
            // This is the request object sent to the workers to generate these static site regional results.
            PersistenceBuffer buffer = PersistenceBuffer.serializeAsJson(analysisWorkerTask);
            fileStorage.saveTauiData(analysisWorkerTask, "request.json", buffer);

            // Save non-fatal warnings encountered applying the scenario to the network for this regional analysis.
            buffer = PersistenceBuffer.serializeAsJson(network.scenarioApplicationWarnings);
            fileStorage.saveTauiData(analysisWorkerTask, "warnings.json", buffer);

            // Save transit route data that allows rendering paths with the Transitive library in a separate file.
            TransitiveNetwork transitiveNetwork = new TransitiveNetwork(network.transitLayer);
            buffer = PersistenceBuffer.serializeAsJson(transitiveNetwork);
            fileStorage.saveTauiData(analysisWorkerTask, "transitive.json", buffer);
        } catch (Exception e) {
            LOG.error("Exception saving static metadata: {}", ExceptionUtils.stackTraceString(e));
            throw new RuntimeException(e);
        }
    }

}
