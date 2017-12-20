package com.conveyal.r5.analyst.cluster;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
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
import java.util.concurrent.ArrayBlockingQueue;
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

    public static final int POLL_TIMEOUT = 10 * 1000; // TODO add units (milliseconds)

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    final TransportNetworkCache transportNetworkCache;

    /**
     * If this value is non-negative, the worker will not actually do any work. It will just report all tasks
     * as completed immediately, but will fail to do so on the given percentage of tasks. This is used in testing task
     * re-delivery and overall broker sanity.
     */
    public int dryRunFailureRate = -1;

    /** How long (minimum, in milliseconds) should this worker stay alive after processing a single-point task. */
    public static final int SINGLE_KEEPALIVE_MINUTES = 30;

    /** How long (minimum, in milliseconds) should this worker stay alive after processing a regional job task. */
    public static final int REGIONAL_KEEPALIVE_MINUTES = 1;

    /** should this worker shut down automatically */
    public final boolean autoShutdown;

    long startupTime, nextShutdownCheckTime;



    public static final Random random = new Random();

    /** The common root of all API URLs contacted by this broker, e.g. http://localhost:7070/api/ */
    protected String brokerBaseUrl;

    static final HttpClient httpClient = makeHttpClient();

    /**
     * This has been pulled out into a method so the broker can also make a similar http client.
     */
    public static HttpClient makeHttpClient () {
        PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager();
        mgr.setDefaultMaxPerRoute(20);
        int timeout = 10 * 1000; // TODO should this be a symbolic constant such as POLL_TIMEOUT ?
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
     * Queues for high-priority interactive tasks and low-priority batch tasks.
     * Should be plenty long enough to hold all that have come in - we don't need to block on polling the manager.
     */
    private ThreadPoolExecutor highPriorityExecutor, batchExecutor;

    /** Thread pool executor for delivering priority tasks. */
    private ThreadPoolExecutor taskDeliveryExecutor;

    /** The HTTP server that receives single-point requests. */
    private spark.Service httpService;

    public AnalystWorker(Properties config) {
        // grr this() must be first call in constructor, even if previous statements do not have side effects.
        // Thanks, Java.
        this(config, new TransportNetworkCache(Boolean.parseBoolean(
            config.getProperty("work-offline", "false")) ? null : config.getProperty("graphs-bucket"),
            new File(config.getProperty("cache-dir", "cache/graphs"))));
    }

    public AnalystWorker(Properties config, TransportNetworkCache cache) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker {} starting at {}", machineId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        // PARSE THE CONFIGURATION

        // First, check whether we are running Analyst offline.
        workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        if (workOffline) {
            LOG.info("Working offline. Avoiding internet connections and hosted services.");
        }

        String addr = config.getProperty("broker-address", "localhost");
        String port = config.getProperty("broker-port", "7070");
        this.brokerBaseUrl = String.format("http://%s:%s/api", addr, port);

        // set the initial graph affinity of this worker (if it is not in the config file it will be
        // set to null, i.e. no graph affinity)
        // we don't actually build the graph now; this is just a hint to the broker as to what
        // graph this machine was intended to analyze.
        this.networkId = config.getProperty("initial-graph-id");

        this.gridCache = new GridCache(config.getProperty("pointsets-bucket"));
        this.transportNetworkCache = cache;
        Boolean autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown"));
        this.autoShutdown = autoShutdown == null ? false : autoShutdown;

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
     * It maintains a small local queue so the worker doesn't idle while fetching new tasks.
     */
    @Override
    public void run() {

        // Create executors with up to one thread per processor.
        // fix size of highPriorityExecutor to avoid broken pipes when threads are killed off before they are done sending data
        // (not confirmed if this actually works)
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        highPriorityExecutor = new ThreadPoolExecutor(availableProcessors, availableProcessors, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(255));
        highPriorityExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        batchExecutor = new ThreadPoolExecutor(1, availableProcessors, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(availableProcessors * 2));
        batchExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        taskDeliveryExecutor = new ThreadPoolExecutor(1, availableProcessors, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(255));
        // can't use CallerRunsPolicy as that would cause deadlocks, calling thread is writing to inputstream
        taskDeliveryExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

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
        spark.Service httpService = spark.Service.ignite()
            .port(7080)
            .threadPool(10);
        httpService.post("/single", new AnalysisWorkerController(this)::handleSinglePoint);

        // Main polling loop to fill the work queues.
        while (true) {
            List<AnalysisTask> tasks = getSomeWork();
            if (tasks == null || tasks.isEmpty()) {
                // Either there was no work, or some kind of error occurred.
                considerShuttingDown();
                LOG.info("Polling the broker did not yield any regional tasks. Sleeping {} sec.", POLL_TIMEOUT);
                try {
                    TimeUnit.SECONDS.sleep(POLL_TIMEOUT);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }
            for (AnalysisTask task : tasks) {
                // attempt to enqueue, waiting if the queue is full
                while (true) {
                    try {
                        batchExecutor.execute(() -> this.handleOneRequest(task));
                        break;
                    } catch (RejectedExecutionException e) {
                        // queue is full, wait 200ms and try again
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e1) { /* nothing */}
                    }
                }
            }
        }
    }

    /**
     * This is the callback that processes a single task and returns the results upon completion.
     * It may be called several times simultaneously on different executor threads.
     * This returns a byte array but only for single point requests at this point.
     */
    protected byte[] handleOneRequest(AnalysisTask request) {
        // Record the fact that the worker is busy so it won't shut down.
        lastRegionalTaskTime = System.currentTimeMillis();
        if (dryRunFailureRate >= 0) {
            // This worker is running in test mode.
            // It should report all work as completed without actually doing anything,
            // but will fail a certain percentage of the time.
            try {
                Thread.sleep(random.nextInt(5000) + 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (random.nextInt(100) >= dryRunFailureRate) {
                taskCompleted(request, null);
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

            // FIXME Single and regional tasks are now handled almost identically here. Only the gzipping is different, and that's handled in the caller.
            TravelTimeComputer computer = new TravelTimeComputer(request, transportNetwork, gridCache);
            if (request.isHighPriority()) {
                // This is a single point task.
                // We want to gzip the data before sending it back to the broker.
                // Compression ratios here are extreme (100x is not uncommon).
                // We had many connection reset by peer, buffer overflows on large files.
                // Handle gzipping with HTTP headers
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                computer.write(outputStream);
                return outputStream.toByteArray();
            } else {
                // This is a regional task.
                // These used to be returned via an SQS queue. Now returning directly to the backend via the HTTP API.
                // The SQS queue was probably just working around server IO speed problems caused by AWS IOPS limits.
                // Use of a byte array output stream here is a bit redundant since the grid writer already has a
                // byte array in it.
                // Write results into a byte array
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                computer.write(outputStream);
                taskCompleted(request, outputStream.toByteArray());
            }
        } catch (Exception ex) {
            // Catch any exceptions that were not handled by more specific catch clauses above.
            // This ensures that some form of error message is passed all the way back up to the web UI.
            TaskError taskError = new TaskError(ex);
            LOG.error("An error occurred while routing", ex);
            reportTaskErrors(request.taskId, HttpStatus.INTERNAL_SERVER_ERROR_500, Arrays.asList(taskError));
        }
        return null;
    }

    /**
     * @return null if no work could be fetched.
     */
    public List<AnalysisTask> getSomeWork () {
        // Run a POST task (poll for work)
        // The graph and r5 commit of this worker are indicated in the task body.
        // TODO return any work results in this same call
        String url = brokerBaseUrl + "/dequeue";
        HttpPost httpPost = new HttpPost(url);
        WorkerStatus workerStatus = new WorkerStatus();
        workerStatus.loadStatus(this);
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
            // Use the lenient object mapper here in case the broker belongs to a newer
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
     * We have two kinds of output from a worker: we can either write to an object in a bucket on S3, or we can stream
     * output over HTTP to a waiting web service caller. This function handles the latter case. It connects to the
     * cluster broker, signals that the task with a certain ID is being completed, and posts the result back through the
     * broker. The broker then passes the result on to the original requester (usually the analysis web UI).
     *
     * This function will run the HTTP Post operation in a new thread so that this function can return, allowing its
     * caller to write data to the input stream it passed in. This arrangement avoids broken pipes that can happen
     * when the calling thread dies. TODO clarify when and how which thread can die.
     */
    public void finishPriorityTask(AnalysisTask request, InputStream result, String contentType, String contentEncoding) {
        //CountingInputStream is = new CountingInputStream(result);

        LOG.info("Returning high-priority results for task {}", request.taskId);

        String url = brokerBaseUrl + String.format("/complete/success/%s", request.taskId);
        HttpPost httpPost = new HttpPost(url);

        // TODO reveal any errors etc. that occurred on the worker.
        httpPost.setEntity(new InputStreamEntity(result));
        httpPost.setHeader("Content-Type", contentType);
        if (contentEncoding != null) httpPost.setHeader("Content-Encoding", contentEncoding);
        taskDeliveryExecutor.execute(() -> {
            try {
                HttpResponse response = httpClient.execute(httpPost);
                // Signal the http client library that we're done with this response object, allowing connection reuse.
                EntityUtils.consumeQuietly(response.getEntity());

                //LOG.info("Returned {} bytes to the broker for task {}", is.getCount(), clusterRequest.taskId);

                if (response.getStatusLine().getStatusCode() == 200) {
                    LOG.info("Successfully marked task {} as completed.", request.taskId);
                } else if (response.getStatusLine().getStatusCode() == 404) {
                    LOG.info("Task {} was not marked as completed because it doesn't exist.", request.taskId);
                } else {
                    LOG.info("Failed to mark task {} as completed, ({}).", request.taskId,
                            response.getStatusLine());
                }
            } catch (Exception e) {
                LOG.warn("Failed to mark task {} as completed.", request.taskId, e);
            }
        });
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
     * Tell the broker that the given message has been successfully processed by a worker.
     * The result of the task is supplied as an array of bytes.
     * TODO supply it in a typed way, with metadata and a grid object.
     */
    public void taskCompleted(AnalysisTask request, byte[] result) {
        String url = brokerBaseUrl + String.format("/complete/%s/%s", request.jobId, request.taskId);
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-type", "application/octet-stream"); // invent our own type application/analysis-grid ?
        httpPost.setEntity(new ByteArrayEntity(result));
        try {
            HttpResponse response = httpClient.execute(httpPost);
            // Signal the http client library that we're done with this response object, allowing connection reuse.
            EntityUtils.consumeQuietly(response.getEntity());
            if (response.getStatusLine().getStatusCode() == 200) {
                LOG.info("Successfully deleted task {} on job {}.", request.taskId, request.jobId);
            } else {
                LOG.warn("Failed to delete task {} on job {} (HTTP status {}).",
                        request.taskId, request.jobId, response.getStatusLine());
            }
        } catch (Exception e) {
            LOG.warn("Failed to delete task {}. Exception: {}", request.taskId, e.toString());
        }
    }

    /**
     * Requires a worker configuration, which is a Java Properties file with the following
     * attributes.
     *
     * broker-address               address of the broker, without protocol or port
     * broker-port                  port broker is running on, default 80.
     * graphs-bucket                S3 bucket in which graphs are stored.
     * pointsets-bucket             S3 bucket in which pointsets are stored
     * auto-shutdown                Should this worker shut down its machine if it is idle (e.g. on throwaway cloud instances)
     * statistics-queue             SQS queue to which to send statistics (optional)
     * initial-graph-id             The graph ID for this worker to start on
     */
    public static void main(String[] args) {
        LOG.info("Starting R5 Analyst Worker version {}", R5Version.version);
        LOG.info("R5 commit is {}", R5Version.commit);
        LOG.info("R5 describe is {}", R5Version.describe);

        Properties config = new Properties();

        try {
            File cfg;
            if (args.length > 0)
                cfg = new File(args[0]);
            else
                cfg = new File("worker.conf");

            InputStream cfgis = new FileInputStream(cfg);
            config.load(cfgis);
            cfgis.close();
        } catch (Exception e) {
            LOG.info("Error loading worker configuration", e);
            return;
        }

        try {
            new AnalystWorker(config).run();
        } catch (Exception e) {
            LOG.error("Error in analyst worker", e);
            return;
        }
    }

    public static enum WorkType {
        SINGLE, REGIONAL;
    }

}
