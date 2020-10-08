package com.conveyal.analysis.controllers;

import com.amazonaws.services.s3.Headers;
import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.BackendMain;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.JobStatus;
import com.conveyal.analysis.components.broker.WorkerObservation;
import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.SinglePointEvent;
import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.HttpStatus;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.mongodb.QueryBuilder;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.util.EntityUtils;
import org.mongojack.DBCursor;
import org.mongojack.DBProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This is a Spark HTTP controller to handle connections from workers reporting their status and requesting work.
 * It also handles connections from the front end for single-point requests.
 * This API replaces what used to be a separate broker process running outside the Analysis backend.
 * The core task queueing and distribution logic is supplied by an instance of the Broker object.
 * The present class should just wrap that functionality with an HTTP API.
 *
 * Workers used to long-poll and hold connections open, allowing them to receive tasks instantly, as soon as the tasks
 * are enqueued. However, this adds a lot of complexity since we need to suspend the open connections and clear them
 * out when the connections are closed. The workers now short-poll, which allows a simple, standard HTTP API.
 * We don't care if it takes a few seconds for workers to start consuming regional tasks.
 *
 * Single point (high priority) tasks are instead handled with a proxy-like push approach.
 * TODO refactor into TaskBroker and WebAPIServer components
 */
public class BrokerController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerController.class);

    /** For convenience, a local reference to the shared JSON object codec. */
    private static ObjectMapper jsonMapper = JsonUtilities.objectMapper;

    // Component Dependencies

    private final Broker broker;

    private final EventBus eventBus;

    public BrokerController (Broker broker, EventBus eventBus) {
        this.broker = broker;
        this.eventBus = eventBus;
    }

    /**
     * This HTTP client contacts workers to send them single-point tasks for immediate processing.
     * TODO we should eventually switch to the new Java standard HttpClient.
     */
    private static HttpClient httpClient = AnalysisWorker.makeHttpClient();

    /**
     * Spark handler functions return Objects.
     * Spark's DefaultSerializer calls toString on those objects and converts the resulting string to UTF-8 bytes.
     * The class spark.webserver.serialization.Serializer only has a few really basic subclasses.
     * In the past we've supplied a ResponseTransformer to these mapping functions to serialize our results.
     * It seems like we should define a Serializer, but Spark provides no setters for MatcherFilter.serializerChain.
     * The other solution (which I've adopted here) is to explicitly serialize our results to JSON and just return
     * a JSON string from our HTTP handler methods.
     */
    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.head("", this::headHandler);
        sparkService.post("/internal/poll", this::workerPoll);
        sparkService.get("/api/jobs", this::getAllJobs);
        sparkService.get("/api/workers", this::getAllWorkers);
        sparkService.post("/api/analysis", this::singlePoint); // TODO rename HTTP path to "single" or something
    }

    /**
     * Handler for single-origin requests. This endpoint is contacted by the frontend rather than the worker, and
     * causes a single-point task to be pushed to an appropriate worker for immediate processing. These requests
     * typically come from an interactive session where the user is moving the origin point around in the web UI.
     * Unlike regional jobs where workers pull tasks from a queue, single point tasks work like a proxy or load balancer.
     * Note that we are using an Apache HTTPComponents client to contact the worker, within a Spark (Jetty) handler.
     * We could use the Jetty HTTP client (which would facilitate exactly replicating request and response headers when
     * we forward the request to a worker), but since Spark wraps the internal Jetty request/response objects, we
     * don't gain much. We should probably switch to the Jetty HTTP client some day when we get rid of Spark.
     * There is also a Jetty proxy module that may be too simple for what we're doing here.
     * @return whatever the worker responds, usually an input stream. Spark serializer chain can properly handle streams.
     */
    private Object singlePoint(Request request, Response response) {
        // Deserialize the task in the request body so we can see what kind of worker it wants.
        // Perhaps we should allow both travel time surface and accessibility calculation tasks to be done as single points.
        // AnalysisRequest (backend) vs. AnalysisTask (R5)
        // The accessgroup stuff is copypasta from the old single point controller.
        // We already know the user is authenticated, and we need not check if they have access to the graphs etc,
        // as they're all coded with UUIDs which contain significantly more entropy than any human's account password.
        final String accessGroup = request.attribute("accessGroup");
        final String userEmail = request.attribute("email");
        final long startTimeMsec = System.currentTimeMillis();
        AnalysisRequest analysisRequest = objectFromRequestBody(request, AnalysisRequest.class);
        Project project = Persistence.projects.findByIdIfPermitted(analysisRequest.projectId, accessGroup);
        // Transform the analysis UI/backend task format into a slightly different type for R5 workers.
        TravelTimeSurfaceTask task = (TravelTimeSurfaceTask) analysisRequest.populateTask(new TravelTimeSurfaceTask(), project);
        // If destination opportunities are supplied, prepare to calculate accessibility worker-side
        if (notNullOrEmpty(analysisRequest.destinationPointSetIds)){
            // Look up all destination opportunity data sets from the database and derive their storage keys.
            // This is mostly copypasted from the code to create a regional analysis.
            // Ideally we'd reuse the same code in both places.
            // We should refactor the populateTask method (and move it off the request) to take care of all this.
            List<OpportunityDataset> opportunityDatasets = new ArrayList<>();
            for (String destinationPointSetId : analysisRequest.destinationPointSetIds) {
                OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdIfPermitted(
                        destinationPointSetId,
                        accessGroup
                );
                checkNotNull(opportunityDataset, "Opportunity dataset could not be found in database.");
                opportunityDatasets.add(opportunityDataset);
            }
            task.destinationPointSetKeys = opportunityDatasets.stream()
                    .map(OpportunityDataset::storageLocation)
                    .toArray(String[]::new);
            // Also do a preflight validation of the cutoffs and percentiles arrays for all non-TAUI regional tasks.
            // Don't validate cutoffs because those are implied to be [0...120) and generated by the worker itself.
            task.validatePercentiles();
        }
        if (request.headers("Accept").equals("image/tiff")) {
            // If the client requested a Geotiff using HTTP headers (for exporting results to GIS),
            // signal this using a field on the request sent to the worker.
            task.setFormat(TravelTimeSurfaceTask.Format.GEOTIFF);
        } else {
            // The default response format is our own compact grid representation.
            task.setFormat(TravelTimeSurfaceTask.Format.GRID);
        }
        WorkerCategory workerCategory = task.getWorkerCategory();
        String address = broker.getWorkerAddress(workerCategory);
        if (address == null) {
            // There are no workers that can handle this request. Request some.
            WorkerTags workerTags = new WorkerTags(accessGroup, userEmail, project._id, project.regionId);
            broker.createOnDemandWorkerInCategory(workerCategory, workerTags);
            // No workers exist. Kick one off and return "service unavailable".
            response.header("Retry-After", "30");
            return jsonResponse(response, HttpStatus.ACCEPTED_202, "Starting routing server. Expect status updates within a few minutes.");
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            // FIXME the tracking of which workers are starting up should really be encapsulated using a "start up if needed" method.
            broker.recentlyRequestedWorkers.remove(workerCategory);
        }
        String workerUrl = "http://" + address + ":7080/single"; // TODO remove hard-coded port number.
        LOG.info("Re-issuing HTTP request from UI to worker at {}", workerUrl);
        HttpPost httpPost = new HttpPost(workerUrl);
        // httpPost.setHeader("Accept", "application/x-analysis-time-grid");
        // TODO Explore: is this unzipping and re-zipping the result from the worker?
        httpPost.setHeader("Accept-Encoding", "gzip");
        HttpEntity entity = null;
        try {
            // Serialize and send the R5-specific task (not the original one the broker received from the UI)
            httpPost.setEntity(new ByteArrayEntity(JsonUtil.objectMapper.writeValueAsBytes(task)));
            HttpResponse workerResponse = httpClient.execute(httpPost);
            // Mimic the status code sent by the worker.
            response.status(workerResponse.getStatusLine().getStatusCode());
            // Mimic headers sent by the worker. We're mostly interested in Content-Type, maybe Content-Encoding.
            // We do not want to mimic all headers like Date, Server etc.
            Header contentTypeHeader = workerResponse.getFirstHeader(Headers.CONTENT_TYPE);
            response.header(contentTypeHeader.getName(), contentTypeHeader.getValue());
            LOG.info("Returning worker response to UI with status code {} and content type {}",
                    workerResponse.getStatusLine(), contentTypeHeader.getValue());
            // This header will cause the Spark Framework to gzip the data automatically if requested by the client.
            response.header("Content-Encoding", "gzip");
            entity = workerResponse.getEntity();
            // Only record activity on successful requests, to avoid polling noise.
            // All other eventbus usage is in Broker, a sign that most of this method should be factored out of the controller.
            if (response.status() == 200) {
                int durationMsec = (int) (System.currentTimeMillis() - startTimeMsec);
                eventBus.send(new SinglePointEvent(task.scenarioId, durationMsec).forUser(userEmail, accessGroup));
            }
            // If you return a stream to the Spark Framework, its SerializerChain will copy that stream out to the
            // client, but does not then close the stream. HttpClient waits for the stream to be closed to return the
            // connection to the pool. In order to be able to close the stream in code we control, we buffer the
            // response in a byte buffer before resending it. NOTE: The fact that we're buffering before re-sending
            // probably degrades the perceived responsiveness of single-point requests.
            return ByteStreams.toByteArray(entity.getContent());
        } catch (SocketTimeoutException ste) {
            LOG.info("Timeout waiting for response from worker.");
            // Aborting the request might help release resources - we had problems with exhausting connection pools here.
            httpPost.abort();
            return jsonResponse(response, HttpStatus.BAD_REQUEST_400, "Routing server timed out. For the " +
                    "complexity of this scenario, your request may have too many simulated schedules. If you are " +
                    "using Routing Engine version < 4.5.1, your scenario may still be in preparation and you should " +
                    "try again in a few minutes.");
        } catch (NoRouteToHostException nrthe){
            LOG.info("Worker in category {} was previously cataloged but is not reachable now. This is expected if a " +
                    "user made a single-point request within WORKER_RECORD_DURATION_MSEC after shutdown.", workerCategory);
            httpPost.abort();
            broker.unregisterSinglePointWorker(workerCategory);
            return jsonResponse(response, HttpStatus.ACCEPTED_202, "Switching routing server");
        } catch (Exception e) {
            throw AnalysisServerException.unknown(e);
        } finally {
            // If the HTTP response entity is non-null close the associated input stream, which causes the HttpClient
            // to release the TCP connection back to its pool. This is critical to avoid exhausting the pool.
            EntityUtils.consumeQuietly(entity);
        }
    }

    /**
     * TODO respond to HEAD requests. For some reason we needed to implement HEAD, for proxies or caches?
     */
    private Object headHandler(Request request, Response response) {
        return null;
    }

    /**
     * This method factors out a few lines of common code that appear in all handlers that produce JSON responses.
     * Set the response status code and media type, then serialize an object to JSON to serve as a response.
     *
     * @param response the response object on which to set the status code and media type
     * @param statusCode the HTTP status code for this result
     * @param object the object to be serialized
     * @return the serialized object as a String, which can then be returned from the Spark handler function
     */
    private String jsonResponse (Response response, int statusCode, Object object) {
        response.status(statusCode);
        response.type("application/json; charset=utf-8");
        // Convert Strings to a Map to yield meaningful JSON.
        if (object instanceof String) {
            object = ImmutableMap.of("message", object);
        }
        // Unfortunately we can't just call response.body(jsonMapper.writeValueAsBytes(object));
        // because that only accepts a String parameter.
        // We could call response.body(jsonMapper.writeValueAsBytes(object));
        // but then the calling handler functions need to explicitly return null which is weird.
        try {
            return jsonMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw AnalysisServerException.unknown(e);
        }
    }

    /**
     * Fetch status of all unfinished jobs as a JSON list.
     */
    private String getAllJobs(Request request, Response response) {
        enforceAdmin(request);
        Collection<JobStatus> jobStatuses = broker.getAllJobStatuses();
        for (JobStatus jobStatus : jobStatuses) {
            jobStatus.regionalAnalysis = Persistence.regionalAnalyses.find(
                    QueryBuilder.start("_id").is(jobStatus.jobId).get(),
                    DBProjection.exclude("request.scenario.modifications")
            ).next();
        }
        return jsonResponse(response, HttpStatus.OK_200, jobStatuses);
    }

    /**
     * Report all workers that have recently contacted the broker as JSON list.
     */
    private String getAllWorkers(Request request, Response response) {
        enforceAdmin(request);
        Collection<WorkerObservation> observations = broker.getWorkerObservations();
        Map<String, Bundle> bundleForNetworkId = new HashMap<>();
        for (WorkerObservation observation : observations) {
            List<Bundle> bundles = new ArrayList<>();
            for (String networkId : observation.status.networks) {
                Bundle bundle = bundleForNetworkId.get(networkId);
                if (bundle == null) {
                    DBCursor<Bundle> cursor = Persistence.bundles.find(QueryBuilder.start("_id").is(networkId).get());
                    // Bundle for a network may have been deleted
                    if (cursor.hasNext()) {
                        bundle = cursor.next();
                        bundleForNetworkId.put(networkId, bundle);
                    }
                }
                if (bundle != null) {
                    bundles.add(bundle);
                }
            }
            observation.bundles = bundles;
        }

        return jsonResponse(response, HttpStatus.OK_200, observations);
    }

    /**
     * Workers use this endpoint to fetch tasks from job queues. At the same time, they also report their version
     * information, unique ID, loaded networks, etc. as JSON in the request body. They also supply the results of any
     * completed work via this same object. The broker should preferentially send them work they can do efficiently
     * using already loaded networks and scenarios. The method is POST because unlike GETs (which fetch status) it
     * modifies the contents of the task queue.
     */
    private Object workerPoll (Request request, Response response) {

        WorkerStatus workerStatus = objectFromRequestBody(request, WorkerStatus.class);
        List<RegionalWorkResult> perOriginResults = workerStatus.results;

        // Record any regional analysis results that were supplied by the worker and mark them completed.
        for (RegionalWorkResult workResult : perOriginResults) {
            // Prevent the backend from shutting down when it's receiving regional analysis results.
            BackendMain.recordActivityToPreventShutdown();
            broker.handleRegionalWorkResult(workResult);
        }
        // Clear out the results field so it's not visible in the worker list API endpoint.
        workerStatus.results = null;

        // Add this worker to our catalog, tracking its graph affinity and the last time it was seen among other things.
        broker.recordWorkerObservation(workerStatus);
        WorkerCategory workerCategory = workerStatus.getWorkerCategory();
        // See if any appropriate tasks exist for this worker.
        List<RegionalTask> tasks = broker.getSomeWork(workerCategory);
        // If there is no work for the worker, signal this clearly with a "no content" code,
        // so the worker can sleep a while before the next polling attempt.
        if (tasks.isEmpty()) {
            return jsonResponse(response, HttpStatus.NO_CONTENT_204, tasks);
        } else {
            return jsonResponse(response, HttpStatus.OK_200, tasks);
        }
    }

    /**
     * Deserializes an object of the given type from JSON in the body of the supplied Spark request.
     */
    private static <T> T objectFromRequestBody (Request request, Class<T> classe) {
        try {
            return JsonUtil.objectMapper.readValue(request.body(), classe);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void enforceAdmin (Request request) {
        if (!request.<UserPermissions>attribute("permissions").admin) {
            throw AnalysisServerException.forbidden("You do not have access.");
        }
    }

}
