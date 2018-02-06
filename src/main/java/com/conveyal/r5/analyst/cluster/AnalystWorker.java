package com.conveyal.r5.analyst.cluster;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.conveyal.r5.multipoint.MultipointDataStore;
import com.conveyal.r5.multipoint.MultipointMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
 * TODO rename AnalysisWorker
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

    public static final int POLL_WAIT_SECONDS = 10;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    final TransportNetworkCache transportNetworkCache;

    /**
     * If this value is non-negative, the worker will not actually do any work. It will just report all tasks
     * as completed immediately, but will fail to do so on the given percentage of tasks. This is used in testing task
     * re-delivery and overall broker sanity.
     */
    public int dryRunFailureRate = -1;

    /** How long (minimum, in milliseconds) should this worker stay alive after processing a single-point task. */
    /** The minimum amount of time (in minutes) that this worker should stay alive after processing a single-point task. */
    public static final int SINGLE_KEEPALIVE_MINUTES = 30;

    /** The minimum amount of time (in minutes) that this worker should stay alive after processing a regional job task. */
    public static final int REGIONAL_KEEPALIVE_MINUTES = 1;

    /** Whether this worker should shut down automatically when idle. */
    public final boolean autoShutdown;

    long startupTime, nextShutdownCheckTime;

    public static final Random random = new Random();

    /** The common root of all API URLs contacted by this worker, e.g. http://localhost:7070/api/ */
    protected String brokerBaseUrl;

    /** The HTTP client the worker uses to contact the broker and fetch regional analysis tasks. */
    static final HttpClient httpClient = makeHttpClient();

    /**
     * The results of finished work accumulate here, and will be sent in batches back to the broker.
     * All access to this field should be synchronized since it will is written to by multiple threads.
     * We don't want to just wrap it in a SynchronizedList because we need an atomic copy-and-empty operation.
     */
    private List<RegionalWorkResult> workResults = new ArrayList<>();

    /**
     * This has been pulled out into a method so the broker can also make a similar http client.
     */
    public static HttpClient makeHttpClient () {
        PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager();
        mgr.setDefaultMaxPerRoute(20);
        int timeout = 10 * 1000; // TODO should this be a symbolic constant such as POLL_WAIT_SECONDS ?
        SocketConfig cfg = SocketConfig.custom()
                .setSoTimeout(timeout)
                .build();
        mgr.setDefaultSocketConfig(cfg);
        return HttpClients.custom()
                .setConnectionManager(mgr)
                .build();
    }

    GridCache gridCache;

    // Clients for communicating with Amazon web services
    AmazonS3 s3;

    /** The transport network this worker already has loaded, and therefore prefers to work on. */
    String networkId = null;

    /** Information about the EC2 instance (if any) this worker is running on. */
    EC2Info ec2info;

    /**
     * The time the last single point task was processed, in milliseconds since the epoch.
     * Used to check if the machine should be shut down.
     */
    protected long lastSinglePointTime = 0;

    /**
     * The time the last regional task was processed, in milliseconds since the epoch.
     * Used to check if the machine should be shut down.
     */
    protected long lastRegionalTaskTime = 0;

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
        boolean workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        String graphsBucket = workOffline ? null : config.getProperty("graphs-bucket");
        String graphDirectory = config.getProperty("cache-dir", "cache/graphs");
        TransportNetworkCache cache = new TransportNetworkCache(graphsBucket, new File(graphDirectory));
        return new AnalystWorker(config, cache);
    }

    public AnalystWorker(Properties config, TransportNetworkCache cache) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker {} starting at {}", machineId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        // PARSE THE CONFIGURATION TODO move configuration parsing into a separate method.

        // First, check whether we are running Analyst offline.
        workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        if (workOffline) {
            LOG.info("Working offline. Avoiding internet connections and hosted services.");
        }

        {
            String brokerAddress = config.getProperty("broker-address", DEFAULT_BROKER_ADDRESS);
            String brokerPort = config.getProperty("broker-port", DEFAULT_BROKER_PORT);
            this.brokerBaseUrl = String.format("http://%s:%s/api", brokerAddress, brokerPort);
        }

        // set the initial graph affinity of this worker (if it is not in the config file it will be
        // set to null, i.e. no graph affinity)
        // we don't actually build the graph now; this is just a hint to the broker as to what
        // graph this machine was intended to analyze.
        this.networkId = config.getProperty("initial-graph-id");

        this.gridCache = new GridCache(config.getProperty("pointsets-bucket"));
        this.transportNetworkCache = cache;
        this.autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown", "false"));

        // Consider shutting this worker down once per hour, starting 55 minutes after it started up.
        startupTime = System.currentTimeMillis();
        nextShutdownCheckTime = startupTime + 55 * 60 * 1000;

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

        // When creating the S3 and SQS clients use the default credentials chain.
        // This will check environment variables and ~/.aws/credentials first, then fall back on
        // the auto-assigned IAM role if this code is running on an EC2 instance.
        // http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-roles.html
        s3 = new AmazonS3Client();
        s3.setRegion(Region.getRegion(Regions.fromName(ec2info.region)));
    }

    /**
     * Consider shutting down if enough time has passed.
     * The strategy depends on the fact that we are billed by the hour for EC2 machines.
     * We only need to consider shutting them down once an hour. Leaving them alive for the rest of the hour provides
     * immediate compute capacity in case someone starts another job.
     * So a few minutes before each hour of uptime has elapsed, we check how long the machine has been idle.
     */
    public void considerShuttingDown() {
        long now = System.currentTimeMillis();
        if (now > nextShutdownCheckTime && autoShutdown) {
            // Check again exactly one hour later (assumes billing in one-hour increments)
            nextShutdownCheckTime += 60 * 60 * 1000;
            if (now > lastSinglePointTime + (SINGLE_KEEPALIVE_MINUTES * 60 * 1000) &&
                now > lastRegionalTaskTime + (REGIONAL_KEEPALIVE_MINUTES * 60 * 1000)) {
                LOG.info("Machine has been idle for at least {} minutes (single point) and {} minutes (regional), " +
                        "shutting down.", SINGLE_KEEPALIVE_MINUTES, REGIONAL_KEEPALIVE_MINUTES);
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

    }

    /**
     * This is the main worker event loop which fetches tasks from a broker and schedules them for execution.
     */
    @Override
    public void run() {

        // Create executors with up to one thread per processor.
        // The default task rejection policy is "Abort".
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>(availableProcessors * 2);
        regionalTaskExecutor = new ThreadPoolExecutor(1, availableProcessors, 60, TimeUnit.SECONDS, taskQueue);

        // If an initial graph ID was provided in the config file, build or load that TransportNetwork on startup.
        // Pre-loading the graph is necessary because if the graph is not cached it can take several
        // minutes to build it. Even if the graph is cached, reconstructing the indices and stop trees
        // can take a while. The UI times out after 30 seconds, so the broker needs to return a response to tell it
        // to try again later within that timespan. The broker can't do that after it's sent a task to a worker,
        // so the worker needs to not come online until it's ready to process requests.
        if (networkId != null) {
            LOG.info("Pre-loading or building network with ID {}", networkId);
            if (transportNetworkCache.getNetwork(networkId) == null) {
                LOG.error("Failed to pre-load transport network {}", networkId);
            } else {
                LOG.info("Done pre-loading network {}", networkId);
            }
        }

        // Before we go into an endless loop polling for regional tasks that can be computed asynchronously, start a
        // single-endpoint web server on this worker to receive single-point requests that must be handled immediately.
        // Trying out the new Spark syntax for non-static configuration.
        sparkHttpService = spark.Service.ignite()
            .port(7080)
            .threadPool(10);
        sparkHttpService.post("/single", new AnalysisWorkerController(this)::handleSinglePoint);

        // Main polling loop to fill the regional work queue.
        // You'd think the ThreadPoolExecutor could just block when the blocking queue is full, but apparently
        // people all over the world have been jumping through hoops to try to achieve this simple behavior
        // with no real success, at least without writing bug and deadlock-prone custom executor services.
        // Two alternative approaches are trying to keep the queue full and waiting for the queue to be almost empty.
        // To keep the queue full, we repeatedly try to add each task to the queue, pausing and retrying when
        // it's full. To wait until it's almost empty, we could use wait() in a loop and notify() as tasks are handled.
        // see https://stackoverflow.com/a/15185004/778449
        while (true) {
            List<AnalysisTask> tasks = getSomeWork();
            if (tasks == null || tasks.isEmpty()) {
                // Either there was no work, or some kind of error occurred.
                considerShuttingDown();
                LOG.info("Polling the broker did not yield any regional tasks. Sleeping {} sec.", POLL_WAIT_SECONDS);
                sleepSeconds(POLL_WAIT_SECONDS);
                continue;
            }
            for (AnalysisTask task : tasks) {
                while (true) {
                    try {
                        // TODO define non-anonymous runnable class to instantiate here
                        regionalTaskExecutor.execute(() -> this.handleOneRequest(task));
                        break;
                    } catch (RejectedExecutionException e) {
                        // Queue is full, wait a bit and try to feed it more tasks.
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
     * This is the callback that processes a single task and returns the results upon completion.
     * It may be called several times simultaneously on different executor threads.
     * This returns a byte array but only for single point requests at this point.
     * It handles both regional and single point requests... and maybe that's a good thing, and we should make them
     * ever more interchangeable.
     *
     * TODO split this out into one "handle immediately" method that returns a byte[] and a void method for async tasks
     */
    protected byte[] handleOneRequest(AnalysisTask request) {
        // Record the fact that the worker is busy so it won't shut down.
        lastRegionalTaskTime = System.currentTimeMillis(); // FIXME both regional and single-point are handled here
        if (dryRunFailureRate >= 0) {
            // This worker is running in test mode.
            // It should report all work as completed without actually doing anything,
            // but will fail a certain percentage of the time.
            // TODO sleep and generate work result in another method
            try {
                Thread.sleep(random.nextInt(5000) + 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (random.nextInt(100) >= dryRunFailureRate) {
                RegionalWorkResult workResult = new RegionalWorkResult(request.jobId, request.taskId, 1, 1, 1);
                synchronized (workResults) {
                    workResults.add(workResult);
                }
            } else {
                LOG.info("Intentionally failing to complete task for testing purposes {}", request.taskId);
            }
            return null;
        }

        try {
            long startTime = System.currentTimeMillis();
            LOG.info("Handling message {}", request.toString());
            long graphStartTime = System.currentTimeMillis();
            // Get the graph object for the ID given in the task, fetching inputs and building as needed.
            // All requests handled together are for the same graph, and this call is synchronized so the graph will
            // only be built once.
            // Record graphId so we "stick" to this same graph on subsequent polls.
            // TODO allow for a list of multiple cached TransitNetworks.
            networkId = request.graphId;
            // TODO fetch the scenario-applied transportNetwork out here, maybe using OptionalResult instead of exceptions
            TransportNetwork transportNetwork = null;
            try {
                // FIXME ideally we should just be passing the scenario object into this function, and another separate function should get the scenario object from the task.
                transportNetwork = transportNetworkCache.getNetworkForScenario(networkId, request);
            } catch (ScenarioApplicationException scenarioException) {
                // Handle exceptions specifically representing a failure to apply the scenario.
                // These exceptions can be turned into structured JSON.
                // Report the error back to the broker, which can then pass it back out to the client.
                // Any other kinds of exceptions will be caught by the outer catch clause
                reportTaskErrors(request.taskId, HttpStatus.BAD_REQUEST_400, scenarioException.taskErrors);
                return null;
            }

            // If we are generating a static site, there must be a single metadata file for an entire batch of results.
            // Arbitrarily we create this metadata as part of the first task in the job.
            if (request instanceof RegionalTask && request.makeStaticSite && request.taskId == 0) {
                LOG.info("This is the first task in a job that will produce a static site. Writing shared metadata.");
                MultipointMetadata mm = new MultipointMetadata(request, transportNetwork);
                mm.write();
            }

            TravelTimeComputer computer = new TravelTimeComputer(request, transportNetwork, gridCache);
            OneOriginResult oneOriginResult = computer.computeTravelTimes();
            // TODO switch mainly on what's present in the result, not on the request type
            if (request.isHighPriority()) {
                // This is a single point task. Return the travel time grid which will be written back to the client.
                // We want to gzip the data before sending it back to the broker.
                // Compression ratios here are extreme (100x is not uncommon).
                // We had many connection reset by peer, buffer overflows on large files.
                // Handle gzipping with HTTP headers (caller should already be doing this)
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                // This travel time surface is being produced by a single-origin task.
                // We could be making a grid or a TIFF.
                TravelTimeSurfaceTask timeSurfaceTask = (TravelTimeSurfaceTask) request;
                if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GRID) {
                    // Return raw byte array representing grid to caller, for return to client over HTTP.
                    byteArrayOutputStream.write(oneOriginResult.timeGrid.writeGrid());
                    addErrorJson(byteArrayOutputStream, transportNetwork.scenarioApplicationWarnings);
                } else if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GEOTIFF) {
                    oneOriginResult.timeGrid.writeGeotiff(byteArrayOutputStream);
                }
                // FIXME strangeness, only travel time results are returned from method, accessibility results return null and are accumulated for async delivery.
                // Return raw byte array containing grid or TIFF file to caller, for return to client over HTTP.
                byteArrayOutputStream.close();
                return byteArrayOutputStream.toByteArray();
            } else {
                // This is a single task within a regional analysis with many origins.
                if (request.makeStaticSite) {
                    // This is actually a time grid, because we're generating a bunch of those for a static site.
                    OutputStream s3stream = MultipointDataStore.getOutputStream(request, request.taskId + "_times.dat", "application/octet-stream");
                    s3stream.write(oneOriginResult.timeGrid.writeGrid());
                    // TODO ? addErrorJson(s3stream, transportNetwork.scenarioApplicationWarnings);
                    s3stream.close();
                }
                // Accumulate accessibility results to return to the backend in batches.
                // This is usually an accessibility indicator value for one of many origins, but in the case of a static
                // site we still want to return dummy / zero accessibility results so the backend is aware of progress.
                synchronized (workResults) {
                    workResults.add(oneOriginResult.toRegionalWorkResult(request));
                }
            }
        } catch (Exception ex) {
            // Catch any exceptions that were not handled by more specific catch clauses above.
            // This ensures that some form of error message is passed all the way back up to the web UI.
            TaskError taskError = new TaskError(ex);
            LOG.error("An error occurred while routing: {}", ex.toString());
            ex.printStackTrace();
            reportTaskErrors(request.taskId, HttpStatus.INTERNAL_SERVER_ERROR_500, Arrays.asList(taskError));
        }
        return null;
    }

    /**
     * This is somewhat hackish - when we want to return errors to the UI, we just append them as JSON at the end of
     * a binary result. We always append this JSON even when there are no errors so the UI has something to decode,
     * even if it's an empty list.
     */
    public static void addErrorJson (OutputStream outputStream, List<TaskError> scenarioApplicationWarnings) throws IOException {
        LOG.info("Travel time surface written, appending metadata with scenario application {} warnings", scenarioApplicationWarnings.size());
        // We create a single-entry map because this converts easily to a JSON object.
        Map<String, List<TaskError>> errorsToSerialize = new HashMap<>();
        errorsToSerialize.put("scenarioApplicationWarnings", scenarioApplicationWarnings);
        JsonUtilities.objectMapper.writeValue(outputStream, errorsToSerialize);
        LOG.info("Done writing");
    }

    /**
     * @return null if no work could be fetched.
     */
    public List<AnalysisTask> getSomeWork () {
        // Run a POST task (poll for work)
        // The graph and r5 commit of this worker are indicated in the task body.
        // TODO return any work results in this same call
        String url = brokerBaseUrl + "/dequeue"; // TODO rename to "poll" since this does more than dequeue
        HttpPost httpPost = new HttpPost(url);
        WorkerStatus workerStatus = new WorkerStatus();
        workerStatus.loadStatus(this);
        // Include all completed work results when polling the backend.
        // Atomically copy and clear the accumulated work results, while blocking writes from other threads.
        synchronized (workResults) {
            workerStatus.results = new ArrayList<>(workResults);
            workResults.clear();
        }
        httpPost.setEntity(JsonUtilities.objectToJsonHttpEntity(workerStatus));
        try {
            HttpResponse response = httpClient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            if (response.getStatusLine().getStatusCode() == 204) {
                // No work to do.
                return null;
            }
            if (entity == null) {
                return null;
            }
            if (response.getStatusLine().getStatusCode() != 200) {
                // TODO log errors!
                EntityUtils.consumeQuietly(entity);
                return null;
            }
            // Use the lenient object mapper here in case the broker is a newer version so sending unrecognizable fields
            return JsonUtilities.lenientObjectMapper.readValue(entity.getContent(), new TypeReference<List<AnalysisTask>>() {});
        } catch (JsonProcessingException e) {
            LOG.error("JSON processing exception while getting work", e);
        } catch (SocketTimeoutException stex) {
            LOG.debug("Socket timeout while waiting to receive work.");
        } catch (HttpHostConnectException ce) {
            LOG.error("Broker refused connection.");
        } catch (IOException e) {
            LOG.error("IO exception while getting work", e);
        }
        return null;

    }

    /**
     * Report to the broker that the task taskId could not be processed due to errors.
     * The broker should then pass the errors back up to the client that enqueued that task.
     * That objects are always the same type (TaskError) so the client knows what to expect.
     */
    public void reportTaskErrors(int taskId, int httpStatusCode, List<TaskError> taskErrors) {
        String url = brokerBaseUrl + String.format("/complete/%d/%s", httpStatusCode, taskId);
        try {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-type", "application/json");
            httpPost.setEntity(JsonUtilities.objectToJsonHttpEntity(taskErrors));
            // Send the JSON serialized error object to the broker.
            HttpResponse response = httpClient.execute(httpPost);
            // Tell the http client library that we won't do anything with the broker's response, allowing connection reuse.
            EntityUtils.consumeQuietly(response.getEntity());
        } catch (Exception e) {
            LOG.error("An exception occurred while attempting to report an error to the broker:\n" + e.getStackTrace());
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
            LOG.error("Error loading worker configuration, shutting down. " + e.toString());
            return;
        }
        try {
            AnalystWorker.forConfig(config).run();
        } catch (Exception e) {
            LOG.error("Unhandled error in analyst worker, shutting down. " + e.toString());
        }
    }

}
