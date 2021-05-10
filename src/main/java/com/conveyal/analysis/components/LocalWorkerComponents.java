package com.conveyal.analysis.components;

import com.conveyal.analysis.WorkerConfig;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.transit.TransportNetworkCache;

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
        // taskScheduler.repeatRegularly(...);
        // eventBus.addHandlers(...);
    }

}
