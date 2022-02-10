package com.conveyal.analysis.components;

import com.conveyal.analysis.controllers.HttpController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * This Component defines the HTTP API exposed by single point workers, which should only be reachable over the local
 * network within the cluster. It has relatively few endpoints defined: single-point requests forwarded via the
 * backend that must be handled immediately, and requests for vector map tiles that represent scenarios on a worker.
 * This API is distinct from the backend's HttpApi Component, which is contacted by both the UI (via the public network)
 * and the workers. This worker API is listening on a different port than the backend API so that a worker can be
 * running on the same machine as the backend. When testing cluster functionality, e.g. task redelivery, many workers
 * run on the same machine. In that case, this HTTP server is disabled on all workers but one to avoid port conflicts.
 *
 * This could potentially be merged with the backend HttpApi component, with the two differing only by configuration.
 */
public class WorkerHttpApi {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerHttpApi.class);

    public interface Config {
        /**
         * This worker will only listen for incoming single point requests if this field is true when run() is invoked.
         * This should return false for regional-only cluster workers.
         */
        boolean listenForSinglePoint();
    }

    /** The port on which the worker will listen for single point tasks forwarded from the backend. */
    public static final int WORKER_LISTEN_PORT = 7080;

    private spark.Service sparkService;

    private List<HttpController> controllers;

    public WorkerHttpApi (Config config, List<HttpController> controllers) {
        this.controllers = controllers;
        if (config.listenForSinglePoint()) {
            LOG.info("This is a single-point worker. Enabling HTTP API.");
            enable();
        } else {
            LOG.info("This is a regional-analysis-only worker. Worker HTTP API will not be available.");
        }
    }

    /**
     * Most workers don't actually expose an HTTP API. Only the workers designated to handle single point requests do.
     * This method should be called on those specific workers to turn on the HTTP API.
     */
    public void enable () {
        // Use the newer non-static Spark framework syntax.
        // Ideally we would limit the number of threads the worker will use to handle HTTP connections, in order to
        // crudely limit memory consumption and load from simultaneous single point requests. Unfortunately we can't
        // call sparkHttpService.threadPool(NTHREADS) because we get an error message saying we need over 10 threads:
        // "needed(acceptors=1 + selectors=8 + request=1)". Even worse, in container-based testing environments this
        // required number of threads is even higher and any value we specify can cause the server (and tests) to fail.
        // TODO find a more effective way to limit simultaneous computations, e.g. feed them through the regional thread pool.
        sparkService = spark.Service.ignite().port(WORKER_LISTEN_PORT);
        for (HttpController controller : controllers) {
            controller.registerEndpoints(sparkService);
        }
    }

}
