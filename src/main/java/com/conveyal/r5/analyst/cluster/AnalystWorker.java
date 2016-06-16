package com.conveyal.r5.analyst.cluster;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.publish.StaticComputer;
import com.conveyal.r5.publish.StaticSiteRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.profile.RepeatedRaptorProfileRouter;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * This is an exact copy of the AnalystWorker in the OTP repository that's been modified to work with (new)
 * TransitNetworks instead of (old) OTP Graphs.
 * We can afford the maintainability nightmare of duplicating so much code because this is intended to completely
 * replace the old class sooner than later.
 * We don't need to wait for point-to-point routing and detailed walking directions etc. to be available on the new
 * TransitNetwork code to do analysis work with it.
 */
public class AnalystWorker implements Runnable {

    /**
     * worker ID - just a random ID so we can differentiate machines used for computation.
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
     */
    public static final String machineId = UUID.randomUUID().toString().replaceAll("-", "");

    private static final Logger LOG = LoggerFactory.getLogger(AnalystWorker.class);

    public static final String WORKER_ID_HEADER = "X-Worker-Id";

    public static final int POLL_TIMEOUT = 10 * 1000;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    private final TransportNetworkCache transportNetworkCache;

    /**
     * If this value is non-negative, the worker will not actually do any work. It will just report all tasks
     * as completed immediately, but will fail to do so on the given percentage of tasks. This is used in testing task
     * re-delivery and overall broker sanity.
     */
    public int dryRunFailureRate = -1;

    /** How long (minimum, in milliseconds) should this worker stay alive after receiving a single point request? */
    public static final int SINGLE_POINT_KEEPALIVE_MSEC = 15 * 60 * 1000;

    /** should this worker shut down automatically */
    public final boolean autoShutdown;

    public static final Random random = new Random();

    /** Records extra (meta-)data that is not essential to the calculation, such as speed and other performance info. */
    private TaskStatisticsStore taskStatisticsStore;

    /** is there currently a channel open to the broker to receive single point jobs? */
    private volatile boolean sideChannelOpen = false;

    /** Reads and writes JSON. */
    ObjectMapper objectMapper;

    String BROKER_BASE_URL = "http://localhost:9001";

    static final HttpClient httpClient;

    static {
        PoolingHttpClientConnectionManager mgr = new PoolingHttpClientConnectionManager();
        mgr.setDefaultMaxPerRoute(20);

        int timeout = 10 * 1000; // TODO should this be a symbolic constant such as POLL_TIMEOUT ?
        SocketConfig cfg = SocketConfig.custom()
                .setSoTimeout(timeout)
                .build();
        mgr.setDefaultSocketConfig(cfg);

        httpClient = HttpClients.custom()
                .setConnectionManager(mgr)
                .build();
    }

    // Builds and caches (old) Graphs
//    ClusterGraphBuilder clusterGraphBuilder;

    // Of course this will eventually need to be shared between multiple AnalystWorker threads.
    PointSetDatastore pointSetDatastore;

    // Clients for communicating with Amazon web services
    AmazonS3 s3;

    /** The transport network this worker already has loaded, and therefore prefers to work on. */
    String networkId = null;

    long startupTime, nextShutdownCheckTime;

    // Region awsRegion = Region.getRegion(Regions.EU_CENTRAL_1);
    Region awsRegion = Region.getRegion(Regions.US_EAST_1);

    /** AWS instance type, or null if not running on AWS. */
    private String instanceType;

    /** TODO what's this number? */
    long lastHighPriorityRequestProcessed = 0;

    /** If true Analyst is running locally, do not use internet connection and remote services such as S3. */
    private boolean workOffline;

    /**
     * Queues for high-priority interactive tasks and low-priority batch tasks.
     * Should be plenty long enough to hold all that have come in - we don't need to block on polling the manager.
     */
    private ThreadPoolExecutor highPriorityExecutor, batchExecutor;

    public AnalystWorker(Properties config) {
        // print out date on startup so that CloudWatch logs has a unique fingerprint
        LOG.info("Analyst worker starting at {}", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        // PARSE THE CONFIGURATION

        // First, check whether we are running Analyst offline.
        workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        if (workOffline) {
            LOG.info("Working offline. Avoiding internet connections and hosted services.");
        }

        // Set up the stats store.
        String statsQueue = config.getProperty("statistics-queue");
        if (workOffline || statsQueue == null) {
            // A stats store that does nothing.
            this.taskStatisticsStore = s -> { };
        } else {
            this.taskStatisticsStore = new SQSTaskStatisticsStore(statsQueue);
        }

        String addr = config.getProperty("broker-address");
        String port = config.getProperty("broker-port");

        if (addr != null) {
            if (port != null) {
                this.BROKER_BASE_URL = String.format("http://%s:%s", addr, port);
            } else {
                this.BROKER_BASE_URL = String.format("http://%s", addr);
            }
        }

        // set the initial graph affinity of this worker (if it is not in the config file it will be
        // set to null, i.e. no graph affinity)
        // we don't actually build the graph now; this is just a hint to the broker as to what
        // graph this machine was intended to analyze.
        this.networkId = config.getProperty("initial-graph-id");

        this.pointSetDatastore = new PointSetDatastore(10, null, false, config.getProperty("pointsets-bucket"));
        this.transportNetworkCache = new TransportNetworkCache(config.getProperty("graphs-bucket"));
        Boolean autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown"));
        this.autoShutdown = autoShutdown == null ? false : autoShutdown;

        // Consider shutting this worker down once per hour, starting 55 minutes after it started up.
        startupTime = System.currentTimeMillis();
        nextShutdownCheckTime = startupTime + 55 * 60 * 1000;

        // When creating the S3 and SQS clients use the default credentials chain.
        // This will check environment variables and ~/.aws/credentials first, then fall back on
        // the auto-assigned IAM role if this code is running on an EC2 instance.
        // http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-roles.html
        s3 = new AmazonS3Client();
        s3.setRegion(awsRegion);

        /* The ObjectMapper (de)serializes JSON. */
        objectMapper = JsonUtilities.objectMapper;

        instanceType = getInstanceType();
    }

    /**
     * This is the main worker event loop which fetches tasks from a broker and schedules them for execution.
     * It maintains a small local queue so the worker doesn't idle while fetching new tasks.
     */
    @Override
    public void run() {

        // Create executors with up to one thread per processor.
        int nP = Runtime.getRuntime().availableProcessors();
        highPriorityExecutor = new ThreadPoolExecutor(1, nP, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(255));
        highPriorityExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        batchExecutor = new ThreadPoolExecutor(1, nP, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(nP * 2));
        batchExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // If an initial graph ID was provided in the config file, build that TransportNetwork on startup.
        // Prebuilding the graph is necessary because, if the graph is not cached it can take several
        // minutes to build it. Even if the graph is cached, reconstructing the indices and stop trees
        // can take up to a minute. The UI times out after 30 seconds, so the broker needs to return
        // a 503 (Service Not Available) to tell it to try again later. It can't do that after it's
        // sent a request to a worker, so the worker needs to not come online until it's ready to process
        // requests.
        if (networkId != null) {
            LOG.info("Prebuilding graph {}", networkId);
            transportNetworkCache.getNetwork(networkId);
            LOG.info("Done prebuilding graph {}", networkId);
        }

        // Start filling the work queues.
        boolean idle = false;
        while (true) {
            long now = System.currentTimeMillis();
            // Consider shutting down if enough time has passed
            if (now > nextShutdownCheckTime && autoShutdown) {
                if (idle && now > lastHighPriorityRequestProcessed + SINGLE_POINT_KEEPALIVE_MSEC) {
                    LOG.warn("Machine is idle, shutting down.");
                    try {
                        Process process = new ProcessBuilder("sudo", "/sbin/shutdown", "-h", "now").start();
                        process.waitFor();
                    } catch (Exception ex) {
                        LOG.error("Unable to terminate worker", ex);
                    } finally {
                        System.exit(0);
                    }
                }
                nextShutdownCheckTime += 60 * 60 * 1000;
            }
            LOG.debug("Long-polling for work ({} second timeout).", POLL_TIMEOUT / 1000.0);
            // Long-poll (wait a few seconds for messages to become available)
            List<GenericClusterRequest> tasks = getSomeWork(WorkType.REGIONAL);
            if (tasks == null) {
                LOG.debug("Didn't get any work. Retrying.");
                idle = true;
                continue;
            }

            // Enqueue high-priority (interactive) tasks first to ensure they are enqueued
            // even if the low-priority batch queue blocks.
            tasks.stream().filter(t -> t instanceof AnalystClusterRequest && ((AnalystClusterRequest) t).outputLocation == null)
                    .forEach(t -> highPriorityExecutor.execute(() -> {
                        LOG.warn("Handling single point request via normal channel, side channel should open shortly.");
                        this.handleOneRequest(t);
                    }));

            // Enqueue low-priority (batch) tasks; note that this may block anywhere in the process
            logQueueStatus();
            tasks.stream().filter(t -> !(t instanceof AnalystClusterRequest) || ((AnalystClusterRequest) t).outputLocation != null)
                .forEach(t -> {
                    // attempt to enqueue, waiting if the queue is full
                    while (true) {
                        try {
                            batchExecutor.execute(() -> this.handleOneRequest(t));
                            break;
                        } catch (RejectedExecutionException e) {
                            // queue is full, wait 200ms and try again
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e1) { /* nothing */}
                        }
                    }
                });

            // TODO log info about the high-priority queue as well.
            logQueueStatus();
            idle = false;
        }
    }

    /**
     * This is the callback that processes a single task and returns the results upon completion.
     * It may be called several times simultaneously on different executor threads.
     */
    private void handleOneRequest(GenericClusterRequest clusterRequest) {

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
                deleteRequest(clusterRequest);
            } else {
                LOG.info("Intentionally failing to complete task {}", clusterRequest.taskId);
            }
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            LOG.info("Handling message {}", clusterRequest.toString());

            TaskStatistics ts = new TaskStatistics();
            ts.graphId = clusterRequest.graphId;
            ts.awsInstanceType = instanceType;
            ts.jobId = clusterRequest.jobId;
            ts.workerId = machineId;

            long graphStartTime = System.currentTimeMillis();
            // Get the graph object for the ID given in the request, fetching inputs and building as needed.
            // All requests handled together are for the same graph, and this call is synchronized so the graph will
            // only be built once.
            // FIXME this is causing the transportNetwork to be fetched twice, once here and once in handleAnalystRequest.
            TransportNetwork transportNetwork = transportNetworkCache.getNetwork(clusterRequest.graphId);
            // Record graphId so we "stick" to this same graph on subsequent polls.
            // TODO allow for a list of multiple cached TransitNetworks.
            networkId = clusterRequest.graphId;
            // FIXME this stats stuff needs to be moved to where the graph is actually built, or fetch the graph out here.
            ts.graphBuild = (int) (System.currentTimeMillis() - graphStartTime);
            // TODO lazy-initialize all additional indexes on transitLayer
            // ts.graphTripCount = transportNetwork.transitLayer...
            ts.graphStopCount = transportNetwork.transitLayer.getStopCount();

            if (clusterRequest instanceof AnalystClusterRequest)
                this.handleAnalystRequest((AnalystClusterRequest) clusterRequest, ts);
            else if (clusterRequest instanceof StaticSiteRequest.PointRequest)
                this.handleStaticSiteRequest((StaticSiteRequest.PointRequest) clusterRequest, transportNetwork, ts);
            else
                LOG.error("Unrecognized request type {}", clusterRequest.getClass());

            // Record information about the current task so we can analyze usage and efficiency over time.
            ts.total = (int) (System.currentTimeMillis() - startTime);
            taskStatisticsStore.store(ts);
        } catch (Exception ex) {
            LOG.error("An error occurred while routing", ex);
        }

    }

    /** handle a fancy new-fangled static site request */
    private void handleStaticSiteRequest (StaticSiteRequest.PointRequest request, TransportNetwork transportNetwork, TaskStatistics ts) {
        new StaticComputer(request, transportNetwork, ts).run();
    }

    /** Handle a stock Analyst request */
    private void handleAnalystRequest (AnalystClusterRequest clusterRequest, TaskStatistics ts) {
        long startTime = System.currentTimeMillis();

        // We need to distinguish between and handle four different types of requests here:
        // Either vector isochrones or accessibility to a pointset,
        // as either a single-origin priority request (where the result is returned immediately)
        // or a job task (where the result is saved to output location on S3).
        boolean isochrone = (clusterRequest.destinationPointsetId == null);
        boolean singlePoint = (clusterRequest.outputLocation == null);

        if (singlePoint) {
            lastHighPriorityRequestProcessed = startTime;
            if (!sideChannelOpen) {
                openSideChannel();
            }
        }

        ts.lon = clusterRequest.profileRequest.fromLon;
        ts.lat = clusterRequest.profileRequest.fromLat;
        ts.pointsetId = clusterRequest.destinationPointsetId;
        ts.single = singlePoint;

        StreetMode mode;
        if (clusterRequest.profileRequest.accessModes.contains(LegMode.CAR)) mode = StreetMode.CAR;
        else if (clusterRequest.profileRequest.accessModes.contains(LegMode.BICYCLE)) mode = StreetMode.BICYCLE;
        else mode = StreetMode.WALK;

        TransportNetwork transportNetwork =
                transportNetworkCache.getNetworkForScenario(clusterRequest.graphId, clusterRequest.profileRequest);

        // THIS IS A HACK TO TEMPORARILY PREVENT NL NETWORKS FROM USING TRANSFER AND TRAVEL TIME LIMITING
        // IT SHOULD BE REMOVED ASAP AFTER THE MRA PROJECT
        if (transportNetwork.transitLayer.stopIdForIndex.get(0).startsWith("NL:")) {
            LOG.warn("Network contains transit stop from the NL feed. Disabling transfer and travel time limiting.");
            clusterRequest.profileRequest.maxRides = 1000;
            clusterRequest.profileRequest.maxTripDurationMinutes = 48 * 60;
        }
        LOG.info("Maximum number of rides: {}", clusterRequest.profileRequest.maxRides);
        LOG.info("Maximum trip duration: {}", clusterRequest.profileRequest.maxTripDurationMinutes);

        // If this one-to-many request is for accessibility information based on travel times to a pointset,
        // fetch the set of points we will use as destinations.
        final PointSet targets;
        if (isochrone) {
            // This is an isochrone request, search to a regular grid of points.
            targets = transportNetwork.getGridPointSet();
        } else {
            // This is a detailed accessibility request. There is necessarily a destination point set supplied.
            targets = pointSetDatastore.get(clusterRequest.destinationPointsetId);
        }

        // Linkage is performed after applying the scenario because the linkage may be different after street modifications.
        // LinkedPointSets retained withing the unlinked PointSet in a LoadingCache, so only one thread will perform the linkage..
        final LinkedPointSet linkedTargets = targets.link(transportNetwork.streetLayer, mode);

        // Run the core repeated-raptor analysis.
        ResultEnvelope envelope = new ResultEnvelope();
        if (clusterRequest.profileRequest.maxFare < 0) {
            // TODO transportNetwork is implied by linkedTargets.
            RepeatedRaptorProfileRouter router =
                    new RepeatedRaptorProfileRouter(transportNetwork, clusterRequest, linkedTargets, ts);
            try {
                envelope = router.route();
                ts.success = true;
            } catch (Exception ex) {
                // An error occurred. Keep the empty envelope empty and TODO include error information.
                LOG.error("Error occurred in profile request", ex);
                ts.success = false;
            }
        } else {
            // pareto-optimal search on fares

            McRaptorSuboptimalPathProfileRouter router =
                    new McRaptorSuboptimalPathProfileRouter(transportNetwork, clusterRequest, linkedTargets);

            try {
                envelope = router.routeEnvelope();
                ts.success = true;
            } catch (Exception ex) {
                // An error occurred. Keep the empty envelope empty and TODO include error information.
                LOG.error("Error occurred in profile request", ex);
                ts.success = false;
            }
        }

        // Send the ResultEnvelope back to the user.
        // The results are either stored on S3 (for multi-origin jobs) or sent back through the broker
        // (for immediate interactive display of isochrones).
        envelope.id = clusterRequest.id;
        envelope.jobId = clusterRequest.jobId;
        envelope.destinationPointsetId = clusterRequest.destinationPointsetId;
        if (clusterRequest.outputLocation == null) {
            // No output location was provided. Instead of saving the result on S3,
            // return the result immediately via a connection held open by the broker and mark the task completed.
            finishPriorityTask(clusterRequest, envelope);
        } else {
            // Save the result on S3 for retrieval by the UI.
            saveBatchTaskResults(clusterRequest, envelope);
        }
    }

    /** Open a single point channel to the broker to receive high-priority requests immediately */
    private synchronized void openSideChannel () {
        if (sideChannelOpen) {
            return;
        }
        LOG.info("Opening side channel for single point requests.");
        new Thread(() -> {
            sideChannelOpen = true;
            // don't keep single point connections alive forever
            while (System.currentTimeMillis() < lastHighPriorityRequestProcessed + SINGLE_POINT_KEEPALIVE_MSEC) {
                LOG.debug("Awaiting high-priority work");
                try {
                    List<GenericClusterRequest> tasks = getSomeWork(WorkType.SINGLE);

                    if (tasks != null)
                        tasks.stream().forEach(t -> highPriorityExecutor.execute(
                                () -> this.handleOneRequest(t)));

                    logQueueStatus();
                } catch (Exception e) {
                    LOG.error("Unexpected exception getting single point work", e);
                }
            }
            sideChannelOpen = false;
        }).start();
    }

    public List<GenericClusterRequest> getSomeWork(WorkType type) {

        // Run a POST request (long-polling for work) indicating which graph and r5 commit this worker has
        String url = String.join("/", BROKER_BASE_URL, "dequeue", type == WorkType.SINGLE ? "single" : "regional",
                networkId, R5Version.version);
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader(new BasicHeader(WORKER_ID_HEADER, machineId));
        HttpResponse response = null;
        try {
            response = httpClient.execute(httpPost);
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                return null;
            }
            if (response.getStatusLine().getStatusCode() != 200) {
                EntityUtils.consumeQuietly(entity);
                return null;
            }
            return objectMapper.readValue(entity.getContent(), new TypeReference<List<GenericClusterRequest>>() {});
        } catch (JsonProcessingException e) {
            LOG.error("JSON processing exception while getting work", e);
        } catch (SocketTimeoutException stex) {
            LOG.debug("Socket timeout while waiting to receive work.");
        } catch (HttpHostConnectException ce) {
            LOG.error("Broker refused connection. Sleeping before retry.");
            try {
                Thread.currentThread().sleep(5000);
            } catch (InterruptedException e) {
            }
        } catch (IOException e) {
            LOG.error("IO exception while getting work", e);
        }
        return null;

    }

    /**
     * Convert the result envelope and its contents to JSON and gzip it in this thread.
     * Transfer the results to Amazon S3 in another thread, piping between the two.
     */
    private void saveBatchTaskResults (AnalystClusterRequest clusterRequest, ResultEnvelope envelope) {
        String fileName = clusterRequest.id + ".json.gz";
        PipedInputStream inPipe = new PipedInputStream();
        Runnable resultSaverRunnable;
        if (workOffline) {
            resultSaverRunnable = () -> {
                // No internet connection or hosted services. Save to local file.
                try {
                    // TODO make a FakeS3 class that can be used by other components.
                    File fakeS3 = new File("S3", clusterRequest.outputLocation);
                    File jobDirectory = new File(fakeS3, clusterRequest.jobId);
                    File outputFile = new File(jobDirectory, fileName);
                    jobDirectory.mkdirs();
                    OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(outputFile));
                    ByteStreams.copy(inPipe, fileOut);
                } catch (Exception e) {
                    LOG.error("Could not save results locally: {}", e);
                }
            };
        } else {
            resultSaverRunnable = () -> {
                // TODO catch the case where the S3 putObject fails. (call deleteRequest based on PutObjectResult in the runnable)
                // Otherwise the AnalystWorker can freeze piping data to a failed S3 saver thread.
                String s3key = String.join("/", clusterRequest.jobId, fileName);
                s3.putObject(clusterRequest.outputLocation, s3key, inPipe, null);
            };
        };
        new Thread(resultSaverRunnable).start();
        try {
            PipedOutputStream outPipe = new PipedOutputStream(inPipe);
            OutputStream gzipOutputStream = new GZIPOutputStream(outPipe);
            // We could do the writeValue() in a thread instead, in which case both the DELETE and S3 options
            // could consume it in the same way.
            objectMapper.writeValue(gzipOutputStream, envelope);
            gzipOutputStream.close();
            // Tell the broker the task has been handled and should not be redelivered to another worker.
            deleteRequest(clusterRequest);
        } catch (Exception e) {
            // Do not delete task from broker, it will be retried.
            LOG.error("Exception while saving routing result to S3: {}", e.getMessage());
        }
    }

    /**
     * Signal the broker that the given high-priority task is completed, providing a result.
     */
    public void finishPriorityTask(GenericClusterRequest clusterRequest, Object result) {
        String url = BROKER_BASE_URL + String.format("/complete/priority/%s", clusterRequest.taskId);
        HttpPost httpPost = new HttpPost(url);
        try {
            // TODO reveal any errors etc. that occurred on the worker.
            // Really this should probably be done with an InputStreamEntity and a JSON writer thread.
            byte[] serializedResult = objectMapper.writeValueAsBytes(result);
            httpPost.setEntity(new ByteArrayEntity(serializedResult));
            HttpResponse response = httpClient.execute(httpPost);
            // Signal the http client library that we're done with this response object, allowing connection reuse.
            EntityUtils.consumeQuietly(response.getEntity());
            if (response.getStatusLine().getStatusCode() == 200) {
                LOG.info("Successfully marked task {} as completed.", clusterRequest.taskId);
            } else if (response.getStatusLine().getStatusCode() == 404) {
                LOG.info("Task {} was not marked as completed because it doesn't exist.", clusterRequest.taskId);
            } else {
                LOG.info("Failed to mark task {} as completed, ({}).", clusterRequest.taskId,
                        response.getStatusLine());
            }
        } catch (Exception e) {
            LOG.warn("Failed to mark task {} as completed.", clusterRequest.taskId, e);
        }
    }

    /**
     * Tell the broker that the given message has been successfully processed by a worker (HTTP DELETE).
     */
    public void deleteRequest(GenericClusterRequest clusterRequest) {
        String url = BROKER_BASE_URL + String.format("/tasks/%s", clusterRequest.taskId);
        HttpDelete httpDelete = new HttpDelete(url);
        try {
            HttpResponse response = httpClient.execute(httpDelete);
            // Signal the http client library that we're done with this response object, allowing connection reuse.
            EntityUtils.consumeQuietly(response.getEntity());
            if (response.getStatusLine().getStatusCode() == 200) {
                LOG.info("Successfully deleted task {}.", clusterRequest.taskId);
            } else {
                LOG.info("Failed to delete task {} ({}).", clusterRequest.taskId, response.getStatusLine());
            }
        } catch (Exception e) {
            LOG.warn("Failed to delete task {}", clusterRequest.taskId, e);
        }
    }

    /** Get the AWS instance type if applicable */
    public String getInstanceType () {
        try {
            HttpGet get = new HttpGet();
            // see http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
            // This seems very much not EC2-like to hardwire an IP address for getting instance metadata,
            // but that's how it's done.
            get.setURI(new URI("http://169.254.169.254/latest/meta-data/instance-type"));
            get.setConfig(RequestConfig.custom()
                    .setConnectTimeout(2000)
                    .setSocketTimeout(2000)
                    .build()
            );

            HttpResponse res = httpClient.execute(get);

            InputStream is = res.getEntity().getContent();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String type = reader.readLine().trim();
            reader.close();
            return type;
        } catch (Exception e) {
            LOG.info("could not retrieve EC2 instance type, you may be running outside of EC2.");
            return null;
        }
    }

    /** log queue status */
    private void logQueueStatus() {
        LOG.debug("Waiting tasks: high priority: {}, batch: {}", highPriorityExecutor.getQueue().size(), batchExecutor.getQueue().size());
    }

    /**
     * Requires a worker configuration, which is a Java Properties file with the following
     * attributes.
     *
     * broker-address               address of the broker, without protocol or port
     * broker port                  port broker is running on, default 80.
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
