package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.HttpStatus;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.util.ExceptionUtils;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.conveyal.analysis.util.HttpStatus.OK_200;

/**
 * This proxies requests coming from the UI over to any currently active worker for the specified network bundle.
 * This could be used for point-to-point routing or the existing R5 endpoints producing debug tiles of the graph.
 * GTFS data might be better fetched directly from the backend, since it already has an API for examining cached GTFS.
 * This works similarly to the single-point controller, but that has a lot of ad-hoc code in it I don't
 * want to break so I'm keeping this general purpose proxy separate for now. That also allows us to experiment with
 * using Java's built-in HTTP client. This could eventually be expanded to also handle the single point requests.
 */
public class WorkerProxyController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerProxyController.class);

    private final Broker broker;

    public WorkerProxyController (Broker broker) {
        this.broker = broker;
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        // Ideally we'd have region, project, bundle, and worker version parameters.
        // Those could be path parameters, x-conveyal-headers, etc.
        // Path starts with api to ensure authentication
        sparkService.path("/api/workers/:workerVersion/bundles/:bundleId", () -> {
            sparkService.get("", this::getWorkerStatus, JsonUtil.toJson);
            sparkService.get("/*", this::proxyRequest);
        });
    }

    /**
     * Get the address of an existing worker or create a new worker and return `null`.
     */
    private String getOrStartWorker (String bundleId, String workerVersion, UserPermissions userPermissions) {
        Bundle bundle = Persistence.bundles.findByIdIfPermitted(bundleId, userPermissions);
        WorkerCategory workerCategory = new WorkerCategory(bundle._id, workerVersion);
        String address = broker.getWorkerAddress(workerCategory);
        if (address == null) {
            // There are no workers that can handle this request. Request one and ask the UI to retry later.
            WorkerTags workerTags = new WorkerTags(userPermissions, bundle.regionId);
            broker.createOnDemandWorkerInCategory(workerCategory, workerTags);
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            // FIXME the tracking of which workers are starting up should really be encapsulated using a "start up if needed" method.
            broker.recentlyRequestedWorkers.remove(workerCategory);
        }
        return address;
    }

    /**
     * Handler for GET requests to be proxied to a worker. This endpoint is contacted by the frontend.
     * These requests typically come from an interactive session where the user is using the web UI.
     * @return whatever the worker responds, usually an input stream. Spark serializer chain can properly handle streams.
     */
    private Object proxyRequest (Request request, Response response) throws IOException {
        final String bundleId = request.params("bundleId");
        final String workerVersion = request.params("workerVersion");

        String workerAddress = getOrStartWorker(bundleId, workerVersion, UserPermissions.from(request));
        if (workerAddress == null) {
            // There are no workers that can handle this request at this time, ask the UI to retry later.
            response.status(HttpStatus.ACCEPTED_202);
            response.header("Retry-After", "30");
            return JsonUtilities.objectMapper.writeValueAsString(ImmutableMap.of("message", "Starting worker."));
        }

        // Port number is hard-coded until we have a good reason to make it configurable.
        String workerUrl = "http://" + workerAddress + ":7080/" + bundleId + "/" + String.join("/", request.splat());
        LOG.debug("Re-issuing HTTP request from UI to worker at {}", workerUrl);

        HttpClient httpClient = HttpClient.newBuilder().build();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(workerUrl))
                .build();
        try {
            HttpResponse resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            resp.headers().map().forEach((key, value) -> {
                if (!value.isEmpty()) response.header(key, value.get(0));
            });
            response.status(OK_200);
            return resp.body();
        } catch (Exception exception) {
            response.status(HttpStatus.BAD_REQUEST_400);
            return JsonUtilities.objectMapper.writeValueAsString(ImmutableMap.of(
                    "message", exception.getMessage(),
                    "stackTrace", ExceptionUtils.stackTraceString(exception)
            ));
        }
    }

    /**
     * HTTP handler that checks whether a worker exists in the category specified in the request parameters.
     * If the client discovers that no worker exists it can take an action to ensure one starts up.
     */
    private Object getWorkerStatus (Request request, Response response) {
        final String bundleId = request.params("bundleId");
        final String workerVersion = request.params("workerVersion");

        WorkerCategory workerCategory = new WorkerCategory(bundleId, workerVersion);
        String workerAddress = broker.getWorkerAddress(workerCategory);
        if (workerAddress == null) {
            response.status(404);
            return ImmutableMap.of(
                    "message", "Worker does not exist."
            );
        }

        return ImmutableMap.of(
                "message", "Worker ready."
        );
    }

}
