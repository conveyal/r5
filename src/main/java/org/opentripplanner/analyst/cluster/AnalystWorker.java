package org.opentripplanner.analyst.cluster;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.opentripplanner.analyst.PointSet;
import org.opentripplanner.analyst.ResultSet;
import org.opentripplanner.analyst.SampleSet;
import org.opentripplanner.api.model.AgencyAndIdSerializer;
import org.opentripplanner.api.model.JodaLocalDateSerializer;
import org.opentripplanner.api.model.QualifiedModeSetSerializer;
import org.opentripplanner.api.model.TraverseModeSetSerializer;
import org.opentripplanner.profile.RepeatedRaptorProfileRouter;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.graph.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 *
 */
public class AnalystWorker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(AnalystWorker.class);

    public static final String WORKER_ID_HEADER = "X-Worker-Id";

    public static final int POLL_TIMEOUT = 10 * 1000;

    public static final Random random = new Random();

    ObjectMapper objectMapper;

    String BROKER_BASE_URL = "http://localhost:9001";

    String s3Prefix = "analyst-dev";

    private final String workerId = UUID.randomUUID().toString().replace("-", ""); // a unique identifier for each worker so the broker can catalog them

    DefaultHttpClient httpClient = new DefaultHttpClient();

    // Of course this will eventually need to be shared between multiple AnalystWorker threads.
    ClusterGraphBuilder clusterGraphBuilder;

    // Of course this will eventually need to be shared between multiple AnalystWorker threads.
    PointSetDatastore pointSetDatastore;

    // Clients for communicating with Amazon web services
    AmazonS3 s3;

    String graphId = null;
    long startupTime, nextShutdownCheckTime;

    // Region awsRegion = Region.getRegion(Regions.EU_CENTRAL_1);
    Region awsRegion = Region.getRegion(Regions.US_EAST_1);

    boolean isSinglePoint = false;

    public AnalystWorker() {

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
        objectMapper = new ObjectMapper();
        objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // ignore JSON fields that don't match target type

        /* Tell Jackson how to (de)serialize AgencyAndIds, which appear as map keys in routing requests. */
        objectMapper.registerModule(AgencyAndIdSerializer.makeModule());

        /* serialize/deserialize qualified mode sets */
        objectMapper.registerModule(QualifiedModeSetSerializer.makeModule());

        /* serialize/deserialize Joda dates */
        objectMapper.registerModule(JodaLocalDateSerializer.makeModule());

        /* serialize/deserialize traversemodesets */
        objectMapper.registerModule(TraverseModeSetSerializer.makeModule());

        /* These serve as lazy-loading caches for graphs and point sets. */
        clusterGraphBuilder = new ClusterGraphBuilder(s3Prefix + "-graphs");
        pointSetDatastore = new PointSetDatastore(10, null, false, s3Prefix + "-pointsets");

        /* The HTTP Client for talking to the Analyst Broker. */
        HttpParams httpParams = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(httpParams, POLL_TIMEOUT);
        HttpConnectionParams.setSoTimeout(httpParams, POLL_TIMEOUT);
        HttpConnectionParams.setSoKeepalive(httpParams, true);
        httpClient.setParams(httpParams);

    }

    @Override
    public void run() {
        // Loop forever, attempting to fetch some messages from a queue and process them.
        boolean idle = false;
        while (true) {
            // Consider shutting down if enough time has passed
            if (System.currentTimeMillis() > nextShutdownCheckTime) {
                if (idle) {
                    try {
                        Process process = new ProcessBuilder("sudo", "/sbin/shutdown", "-h", "now").start();
                        process.waitFor();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        System.exit(0);
                    }
                }
                nextShutdownCheckTime += 60 * 60 * 1000;
            }
            LOG.info("Long-polling for work ({} second timeout).", POLL_TIMEOUT / 1000.0);
            // Long-poll (wait a few seconds for messages to become available)
            // TODO internal blocking queue feeding work threads, polls whenever queue.size() < nProcessors
            List<AnalystClusterRequest> tasks = getSomeWork();
            if (tasks == null) {
                LOG.info("Didn't get any work. Retrying.");
                idle = true;
                continue;
            }
            tasks.parallelStream().forEach(this::handleOneRequest);
            idle = false;
        }
    }

    private void handleOneRequest(AnalystClusterRequest clusterRequest) {
        try {
            LOG.info("Handling message {}", clusterRequest.toString());

            // Get the graph object for the ID given in the request, fetching inputs and building as needed.
            // All requests handled together are for the same graph, and this call is synchronized so the graph will
            // only be built once.
            Graph graph = clusterGraphBuilder.getGraph(clusterRequest.graphId);
            graphId = clusterRequest.graphId; // Record graphId so we "stick" to this same graph on subsequent polls

            // This result envelope will hold the result of the profile or single-time one-to-many search.
            ResultEnvelope envelope = new ResultEnvelope();
            if (clusterRequest.profileRequest != null) {
                SampleSet sampleSet = null;
                if (clusterRequest.destinationPointsetId != null) {
                    // A pointset was specified, calculate travel times to the points in the pointset.
                    // Fetch the set of points we will use as destinations for this one-to-many search
                    PointSet pointSet = pointSetDatastore.get(clusterRequest.destinationPointsetId);
                    sampleSet = pointSet.getOrCreateSampleSet(graph);
                }
                // Passing a null SampleSet parameter will properly return only isochrones in the RangeSet
                RepeatedRaptorProfileRouter router =
                        new RepeatedRaptorProfileRouter(graph, clusterRequest.profileRequest, sampleSet);
                try {
                    router.route();
                    ResultSet.RangeSet results = router.makeResults(clusterRequest.includeTimes, true, false);
                    // put in constructor?
                    envelope.bestCase = results.min;
                    envelope.avgCase = results.avg;
                    envelope.worstCase = results.max;
                    envelope.id = clusterRequest.id;
                    envelope.destinationPointsetId = clusterRequest.destinationPointsetId;
                } catch (Exception ex) {
                    // Leave the envelope empty TODO include error information
                }
            } else {
                // No profile request, this must be a plain one to many routing request.
                RoutingRequest routingRequest = clusterRequest.routingRequest;
                // TODO finish the non-profile case
            }

            if (clusterRequest.outputLocation != null) {
                // Convert the result envelope and its contents to JSON and gzip it in this thread.
                // Transfer the results to Amazon S3 in another thread, piping between the two.
                String s3key = String.join("/", clusterRequest.jobId, clusterRequest.id + ".json.gz");
                PipedInputStream inPipe = new PipedInputStream();
                PipedOutputStream outPipe = new PipedOutputStream(inPipe);
                new Thread(() -> {
                    s3.putObject(clusterRequest.outputLocation, s3key, inPipe, null);
                }).start();
                OutputStream gzipOutputStream = new GZIPOutputStream(outPipe);
                // We could do the writeValue() in a thread instead, in which case both the DELETE and S3 options
                // could consume it in the same way.
                objectMapper.writeValue(gzipOutputStream, envelope);
                gzipOutputStream.close();
                // DELETE the task from the broker, confirming it has been handled and should not be re-delivered.
                deleteRequest(clusterRequest);
            } else {
                // No output location on S3 specified, return the result via the broker and mark the task completed.
                finishPriorityTask(clusterRequest, envelope);
            }
        } catch (Exception ex) {
            LOG.error("An error occurred while routing: " + ex.getMessage());
            ex.printStackTrace();
        }

    }

    public List<AnalystClusterRequest> getSomeWork() {

        // Run a GET request (long-polling for work) indicating which graph this worker prefers to work on
        String url = BROKER_BASE_URL + "/" + graphId;
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader(new BasicHeader(WORKER_ID_HEADER, workerId));
        HttpResponse response = null;
        try {
            response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                return null;
            }
            if (response.getStatusLine().getStatusCode() != 200) {
                EntityUtils.consumeQuietly(entity);
                return null;
            }
            return objectMapper.readValue(entity.getContent(), new TypeReference<List<AnalystClusterRequest>>() {
            });
        } catch (JsonProcessingException e) {
            LOG.error("JSON processing exception while getting work: {}", e.getMessage());
        } catch (SocketTimeoutException stex) {
            LOG.error("Socket timeout while waiting to receive work.");
        } catch (HttpHostConnectException ce) {
            LOG.error("Broker refused connection. Sleeping before retry.");
            try {
                Thread.currentThread().sleep(5000);
            } catch (InterruptedException e) {
            }
        } catch (IOException e) {
            LOG.error("IO exception while getting work.");
            e.printStackTrace();
        }
        return null;

    }

    /**
     * Signal the broker that the given high-priority task is completed, providing a result.
     */
    public void finishPriorityTask(AnalystClusterRequest clusterRequest, Object result) {
        String url = BROKER_BASE_URL + String.format("/priority/%s", clusterRequest.taskId);
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
                LOG.info("Failed to mark task {} as completed.", clusterRequest.taskId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOG.info("Failed to mark task {} as completed.", clusterRequest.taskId);
        }
    }

    /**
     * DELETE the given message from the broker, indicating that it has been processed by a worker.
     */
    public void deleteRequest(AnalystClusterRequest clusterRequest) {
        String url = BROKER_BASE_URL + String.format("/tasks/%s", clusterRequest.taskId);
        HttpDelete httpDelete = new HttpDelete(url);
        try {
            HttpResponse response = httpClient.execute(httpDelete);
            // Signal the http client library that we're done with this response object, allowing connection reuse.
            EntityUtils.consumeQuietly(response.getEntity());
            if (response.getStatusLine().getStatusCode() == 200) {
                LOG.info("Successfully deleted task {}.", clusterRequest.taskId);
            } else {
                LOG.info("Failed to delete task {}.", clusterRequest.taskId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOG.info("Failed to delete task {}", clusterRequest.taskId);
        }
    }

    public static void main(String[] args) {
        new AnalystWorker().run();
    }

}