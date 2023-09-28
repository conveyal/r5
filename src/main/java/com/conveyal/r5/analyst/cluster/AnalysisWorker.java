package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.components.Component;
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
import com.conveyal.r5.transit.TransitLayer;
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
 * This contains the main polling loop used by the worker to pull and asynchronously process regional analysis tasks.
 * It polls the broker requesting work over HTTP, telling the broker what networks and scenarios it has loaded.
 * It also contains methods invoked by the WorkerHttpApi for handling single-point requests, because they use many
 * of the same Components, but it may be clearer in the long run to factor that out.
 * Since this is now placed under Worker we should eventually rename it to something like RegionalTaskProcessor.
 */
public class AnalysisWorker implements Component {

    //  CONFIGURATION

    public interface Config {
        String brokerAddress();
        String brokerPort();
        String initialGraphId();
    }

    // CONSTANTS

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorker.class);

    private static final int POLL_INTERVAL_MIN_SECONDS = 1;
    private static final int POLL_INTERVAL_MAX_SECONDS = 15;
    private static final int POLL_JITTER_SECONDS = 5;
    private static final int QUEUE_SLOTS_PER_PROCESSOR = 8;

    /**
     * This timeout should be longer than the longest expected worker calculation for a single-point request.
     * Preparing networks or linking grids will take longer, but those cases are now handled with
     * WorkerNotReadyException.
     */
    private static final int HTTP_CLIENT_TIMEOUT_SEC = 55;

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

    /**
     * The last time (in milliseconds since the epoch) that we polled for work.
     * The initial value of zero causes the worker to poll the backend immediately on startup avoiding a delay.
     */
    private long lastPollingTime = 0;

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
     * This avoids "slow joiner" syndrome where we wait until all N fetched tasks have finished,
     * but one of the tasks takes much longer than all the rest.
     * This should be long enough to hold all that have come in - we don't need to block on polling the manager.
     * Can this be replaced with the general purpose TaskScheduler component?
     * That will depend whether all TaskScheduler Tasks are tracked in a way intended to be visible to users.
     */
    private ThreadPoolExecutor regionalTaskExecutor;

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
    public void startPolling () {

        // Create an executor with one thread per processor. The default task rejection policy is "Abort".
        // The number of threads will only increase from the core pool size toward the max pool size when the queue is
        // full. We no longer exceed the queue length in normal operation, so the thread pool will remain at core size.
        // "[The] core pool size is the threshold beyond which [an] executor service prefers to queue up the task
        // [rather] than spawn a new thread." https://stackoverflow.com/a/72684387/778449
        // The executor's queue is rather long because some tasks complete very fast and we poll at most once per second.
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        LOG.info("Java reports the number of available processors is: {}", availableProcessors);
        final int maxThreads = availableProcessors;
        final int taskQueueLength = availableProcessors * QUEUE_SLOTS_PER_PROCESSOR;
        LOG.info("Maximum number of regional processing threads is {}, target length of task queue is {}.", maxThreads, taskQueueLength);
        BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>(taskQueueLength);
        regionalTaskExecutor = new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS, taskQueue);

        // This is the main polling loop that fills the regional work queue.
        // Go into an endless loop polling for regional tasks that can be computed asynchronously.
        // You'd think the ThreadPoolExecutor could just block when the blocking queue is full, but apparently
        // people all over the world have been jumping through hoops to try to achieve this simple behavior
        // with no real success, at least without writing bug and deadlock-prone custom executor services.
        // Our current (revised) approach is to slowly spin-wait, checking whether the queue is low and sleeping briefly,
        // then fetching more work only when the queue is getting empty.

        // Allows slowing down polling when not actively receiving tasks.
        boolean receivedWorkLastTime = false;

        // Before first polling the broker, randomly wait a few seconds to spread load when many workers start at once.
        sleepSeconds((new Random()).nextInt(POLL_JITTER_SECONDS));
        while (true) {
            // We establish a lower limit on the wait time between polling to avoid flooding the broker with requests.
            // If worker handles all tasks in its internal queue in less than this time, this is a speed bottleneck.
            // This can happen in areas unconnected to transit and when travel time cutoffs are very low.
            sleepSeconds(POLL_INTERVAL_MIN_SECONDS);
            // Determine whether to poll this cycle - is the queue running empty, or has the maximum interval passed?
            {
                long currentTime = System.currentTimeMillis();
                boolean maxIntervalExceeded = (currentTime - lastPollingTime) > (POLL_INTERVAL_MAX_SECONDS * 1000);
                int tasksInQueue = taskQueue.size();
                // Poll any time we have less tasks in the queue than processors.
                boolean shouldPoll = maxIntervalExceeded || (receivedWorkLastTime && (tasksInQueue < availableProcessors));
                LOG.debug("Last polled {} sec ago. Task queue length is {}.", (currentTime - lastPollingTime)/1000, taskQueue.size());
                if (!shouldPoll) {
                    continue;
                }
                lastPollingTime = currentTime;
            }
            // This will request tasks even when queue is rather full.
            // For now, assume more but smaller task and result chunks is better at leveling broker load.
            int tasksToRequest = taskQueue.remainingCapacity();
            // Alternatively: Only request tasks when queue is short. Otherwise, only report results and status.
            // int tasksToRequest = (tasksInQueue < minQueueLength) ? taskQueue.remainingCapacity() : 0;

            List<RegionalTask> tasks = getSomeWork(tasksToRequest);
            boolean noWorkReceived = tasks == null || tasks.isEmpty();
            receivedWorkLastTime = !noWorkReceived; // Allows variable speed polling on the next iteration.
            if (noWorkReceived) {
                // Either the broker supplied no work or an error occurred.
                continue;
            }
            for (RegionalTask task : tasks) {
                // Executor services require blocking queues of fixed length. Tasks must be enqueued one by one, and
                // may fail with a RejectedExecutionException if we exceed the queue length. We choose queue length
                // and requested number of tasks carefully to avoid overfilling the queue, but should handle the
                // exceptions just in case something is misconfigured.
                try {
                    regionalTaskExecutor.execute(new RegionalTaskRunnable(task));
                } catch (RejectedExecutionException e) {
                    LOG.error("Regional task could not be enqueued for processing (queue length exceeded). Task dropped.");
                }
            }
        }
    }

    /**
     * Runnable inner class which can access the needed methods on AnalysisWorker (handleOneRegionalTask).
     * However that method is only called from these runnables - it could potentially be inlined into run().
     */
    protected class RegionalTaskRunnable implements Runnable {
        RegionalTask task;

        public RegionalTaskRunnable(RegionalTask task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                handleOneRegionalTask(task);
            } catch (Throwable t) {
                LOG.error(
                        "An error occurred while handling a regional task, reporting to backend. {}",
                        ExceptionUtils.stackTraceString(t)
                );
                synchronized (workResults) {
                    workResults.add(new RegionalWorkResult(t, task));
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
                    oneOriginResult,
                    transportNetwork.scenarioApplicationWarnings,
                    transportNetwork.scenarioApplicationInfo,
                    transportNetwork.transitLayer
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

        if (task.injectFault != null) {
            task.injectFault.considerShutdownOrException(task.taskId);
            if (task.injectFault.shouldDropTaskBeforeCompute(task.taskId)) {
                return;
            }
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
        // only be built once. Record the currently loaded network ID to remain on this same graph on subsequent polls.
        networkId = task.graphId;

        // Static site tasks do not specify destinations, but all other regional tasks should.
        // Load the PointSets based on the IDs (actually, full storage keys including IDs) in the task.
        // The presence of these grids in the task will then trigger the computation of accessibility values.
        if (!task.makeTauiSite) {
            task.loadAndValidateDestinationPointSets(pointSetCache);
        }

        // Pull all necessary inputs into cache in a blocking fashion, unlike single-point tasks where prep is async.
        // Avoids auto-shutdown while preloading. Must be done after loading destination pointsets to establish extents.
        // Note we're completely bypassing the async loader here and relying on the older nested LoadingCaches.
        // If those are ever removed, the async loader will need a synchronous mode with per-path blocking (kind of
        // reinventing the wheel of LoadingCache) or we'll need to make preparation for regional tasks async.
        TransportNetwork transportNetwork = networkPreloader.synchronousPreload(task);

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
            oneOriginResult = new OneOriginResult(null, new AccessibilityResult(task), null, null);
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

        public PathResultSummary pathSummaries;

        public double[][][] opportunitiesPerMinute;

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
     * Note that this is very similar to the RegionalWorkResult constructor, and some duplication of logic could be
     * achieved by somehow merging the two.
     */
    public static void addJsonToGrid (
            OutputStream outputStream,
            OneOriginResult oneOriginResult,
            List<TaskError> scenarioApplicationWarnings,
            List<TaskError> scenarioApplicationInfo,
            TransitLayer transitLayer // Only used if oneOriginResult contains paths, can be null otherwise
    ) throws IOException {
        var jsonBlock = new GridJsonBlock();
        jsonBlock.scenarioApplicationInfo = scenarioApplicationInfo;
        jsonBlock.scenarioApplicationWarnings = scenarioApplicationWarnings;
        if (oneOriginResult != null) {
            if (oneOriginResult.accessibility != null) {
                // Due to the application of distance decay functions, we may want to make the shift to non-integer
                // accessibility values (especially for cases where there are relatively few opportunities across the whole
                // study area). But we'd need to control the number of decimal places serialized into the JSON.
                jsonBlock.accessibility = oneOriginResult.accessibility.getIntValues();
            }
            if (oneOriginResult.paths != null) {
                jsonBlock.pathSummaries = new PathResultSummary(oneOriginResult.paths, transitLayer);
            }
            if (oneOriginResult.density != null) {
                jsonBlock.opportunitiesPerMinute = oneOriginResult.density.opportunitiesPerMinute;
            }
        }
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
    public List<RegionalTask> getSomeWork (int tasksToRequest) {
        LOG.debug("Polling backend to report status and request up to {} tasks.", tasksToRequest);
        String url = brokerBaseUrl + "/poll";
        HttpPost httpPost = new HttpPost(url);
        WorkerStatus workerStatus = new WorkerStatus(this);
        workerStatus.maxTasksRequested = tasksToRequest;
        workerStatus.pollIntervalSeconds = POLL_INTERVAL_MAX_SECONDS;
        // Include all completed work results when polling the backend.
        // Atomically copy and clear the accumulated work results, while blocking writes from other threads.
        synchronized (workResults) {
            workerStatus.results = new ArrayList<>(workResults);
            workResults.clear();
        }

        // Compute throughput in tasks per minute and include it in the worker status report.
        // We poll too frequently to compute throughput just since the last poll operation.
        // We may want to reduce polling frequency (with larger queue in worker) and compute shorter-term throughput.
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
                return JsonUtilities.lenientObjectMapper.readValue(
                        responseEntity.getContent(),
                        new TypeReference<List<RegionalTask>>() {}
                );
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
