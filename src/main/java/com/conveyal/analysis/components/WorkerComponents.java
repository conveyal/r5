package com.conveyal.analysis.components;

import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.file.FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.transit.TransportNetworkCache;

/**
 * A common base class for different ways of wiring up components for analysis workers for local/cloud environments.
 * This is analogous to BackendComponents (a common superclass for wiring up the backend for a particular environment).
 * Many of the same components are used in the backend and workers, to increase code reuse and compatibility.
 *
 * For now, unlike BackendComponents this is not abstract because we have only one method (with conditional logic) for
 * wiring up. We expect to eventually have separate LocalWorkerComponents and ClusterWorkerComponents, which will
 * eliminate some of the conditional logic in the configuration.
 */
public abstract class WorkerComponents {

    // This static field is a hack because our worker code currently uses FileStorage deep in the call stack.
    // It's here rather than FileStorage.instance to emphasize that this is a quirk of the Worker code only.
    public static FileStorage fileStorage;

    // INSTANCE FIELDS
    // These are all references to singleton components making up a worker.
    // Unfortunately these fields can't be final because we want to initialize them in subclass constructors.
    // They would need to be set in an unwieldy N-arg constructor.
    public TaskScheduler taskScheduler; // TODO use for regularly recurring backend polling, shutdown, all worker tasks.
    public EventBus eventBus;
    public TransportNetworkCache transportNetworkCache;
    public AnalysisWorker analysisWorker;

}
