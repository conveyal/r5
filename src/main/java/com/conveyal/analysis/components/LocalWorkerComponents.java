package com.conveyal.analysis.components;

import com.conveyal.analysis.controllers.NetworkTileController;
import com.conveyal.components.TaskScheduler;
import com.conveyal.eventbus.EventBus;
import com.conveyal.worker.AnalysisWorker;
import com.conveyal.worker.AnalysisWorkerController;
import com.conveyal.worker.TransportNetworkCache;
import com.conveyal.worker.WorkerComponents;
import com.conveyal.worker.WorkerConfig;
import com.conveyal.worker.WorkerHttpApi;

import java.util.List;

/**
 * Wires up the components for a local worker instance (as opposed to a cloud-hosted worker instance).
 * This establishes the implementations and dependencies between them, and supplies configuration.
 * No conditional logic should be present here.
 * Differences in implementation or configuration are handled by the Components themselves.
 */
public class LocalWorkerComponents extends WorkerComponents {

    /** In local operation, share the gtfs and osm cache components that the backend has already constructed. */
    public LocalWorkerComponents (TransportNetworkCache transportNetworkCache, WorkerConfig config) {
        // GTFS and OSM caches and FileStorage are already referenced in the supplied TransportNetworkCache.
        this.transportNetworkCache = transportNetworkCache;
        // We could conceivably use the same taskScheduler and eventBus from the backend.
        // In fact since we only ever start one worker in local mode, we don't need the components at all.
        // We could construct a single AnalysisWorker(Component) in LocalBackendComponents.
        // The ClusterWorkerComponents could then be all final again.
        taskScheduler = new TaskScheduler(config);
        eventBus = new EventBus(taskScheduler);
        analysisWorker = new AnalysisWorker(fileStorage, transportNetworkCache, eventBus, config);
        workerHttpApi = new WorkerHttpApi(config, List.of(
                new AnalysisWorkerController(analysisWorker),
                new NetworkTileController(transportNetworkCache)
        ));
    }

}
