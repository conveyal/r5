package com.conveyal.analysis.controllers;

import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.util.HttpStatus;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * This proxies requests coming from the UI over to any currently active worker for the specified network bundle.
 * This could be used for point-to-point routing or the existing R5 endpoints producing debug tiles of the graph.
 * GTFS data might be better fetched directly from the backend, since it already has an API for examining cached GTFS.
 * This works similarly to the single-point controller, but that has a lot of ad-hoc code in it I don't
 * wnat to break so I'm keeping this general purpose proxy separate for now. That also allows us to experiment with
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
        sparkService.get("/workerProxy/:bundle/:workerVersion", this::proxyGet);
    }

    /**
     * Handler for GET requests to be proxied to a worker. This endpoint is contacted by the frontend.
     * These requests typically come from an interactive session where the user is using the web UI.
     * @return whatever the worker responds, usually an input stream. Spark serializer chain can properly handle streams.
     */
    private Object proxyGet (Request request, Response response) {

        final long startTimeMsec = System.currentTimeMillis();

        final String bundleId = request.params("bundleId");
        final String workerVersion = request.params("workerVersion");

        WorkerCategory workerCategory = new WorkerCategory(bundleId, workerVersion);
        String address = broker.getWorkerAddress(workerCategory);
        if (address == null) {
            Bundle bundle = null;
            // There are no workers that can handle this request. Request one and ask the UI to retry later.
            final String accessGroup = request.attribute("accessGroup");
            final String userEmail = request.attribute("email");
            WorkerTags workerTags = new WorkerTags(accessGroup, userEmail, "anyProjectId", bundle.regionId);
            broker.createOnDemandWorkerInCategory(workerCategory, workerTags);
            response.status(HttpStatus.ACCEPTED_202);
            response.header("Retry-After", "30");
            response.body("Starting worker.");
            return response;
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            // FIXME the tracking of which workers are starting up should really be encapsulated using a "start up if needed" method.
            broker.recentlyRequestedWorkers.remove(workerCategory);
        }

        String workerUrl = "http://" + address + ":7080/single"; // TODO remove hard-coded port number.
        LOG.info("Re-issuing HTTP request from UI to worker at {}", workerUrl);


        HttpClient httpClient = HttpClient.newBuilder().build();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(workerUrl))
                .header("Accept-Encoding", "gzip") // TODO Explore: is this unzipping and re-zipping the result from the worker?
                .build();
        try {
            response.status(0);
            // content-type response.header();
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception exception) {
            response.status(HttpStatus.BAD_GATEWAY_502);
            response.body(ExceptionUtils.asString(exception));
            return response;
        } finally {

        }
    }

}
