package com.conveyal.worker;

import com.conveyal.components.TaskScheduler;
import com.conveyal.eventbus.EventBus;
import com.conveyal.file.FileStorage;

/**
 * A common base class for different ways of wiring up components for analysis workers for local/cloud environments.
 * This is analogous to BackendComponents (a common superclass for wiring up the backend for a particular environment).
 * Many of the same components are used in the backend and workers, to increase code reuse and compatibility.
 *
 * For now, unlike BackendComponents this is not abstract because we have only one method (with conditional logic) for
 * wiring up. We expect to eventually have separate LocalWorkerComponents and ClusterWorkerComponents, which will
 * eliminate some conditional logic in the configuration.
 */
public abstract class WorkerComponents {

    // INSTANCE FIELDS
    // These are all references to singleton components making up a worker.
    // Unfortunately these fields can't be final because we want to initialize them in subclass constructors.
    // They would need to be set in an unwieldy N-arg constructor.
    public TaskScheduler taskScheduler; // TODO use for regularly recurring backend polling, shutdown, all worker tasks.
    public EventBus eventBus;
    public TransportNetworkCache transportNetworkCache;
    public AnalysisWorker analysisWorker;
    public WorkerHttpApi workerHttpApi;

    public FileStorage fileStorage;
}
