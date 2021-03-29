package com.conveyal.analysis.components;

import com.conveyal.analysis.WorkerConfig;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.file.FileStorage;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.file.S3FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A common base class for different ways of wiring up components for analysis workers for local/cloud environments.
 * This is analogous to BackendComponents (a common superclass for wiring up the backend for a particular environment).
 * Many of the same components are used in the backend and workers, to increase code reuse and compatibility.
 *
 * For now, unlike BackendComponents this is not abstract because we have only one method (with conditional logic) for
 * wiring up. We expect to eventually have separate LocalWorkerComponents and ClusterWorkerComponents, which will
 * eliminate some of the conditional logic in the configuration.
 */
public class WorkerComponents {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerComponents.class);

    // INSTANCE FIELDS
    // These are all references to singleton components making up a worker.

    // This static field is a hack because our worker code currently uses FileStorage deep in the call stack.
    // It's here rather than FileStorage.instance to emphasize that this is a quirk of the Worker code only.
    public static FileStorage fileStorage;
    public final TaskScheduler taskScheduler; // TODO use for regularly recurring backend polling + all worker tasks.
    public final EventBus eventBus;
    public final OSMCache osmCache;
    public final GTFSCache gtfsCache;
    public final TransportNetworkCache transportNetworkCache;
    public final AnalysisWorker analysisWorker; // TODO rename to AnalystWorker in subsequent commit
    public final NetworkPreloader networkPreloader;

    // CONSTRUCTORS
    // Having these two constructors allows either loading from a file or programmatically passing in config properties.
    // We do the latter when when launching local workers.

    public WorkerComponents () {
        this(WorkerConfig.fromDefaultFile());
    }

    // TODO pass in already constructed gtfs and osm caches to share them with the backend.
    public WorkerComponents (WorkerConfig config) {
        // Eventually, this conditional wiring may be replaced with polymorphism (subclasses) and Config interfaces.
        if (config.workOffline()) {
            fileStorage = new LocalFileStorage(config);
        } else {
            fileStorage = new S3FileStorage(config);
        }
        osmCache = new OSMCache(fileStorage);
        gtfsCache = new GTFSCache(fileStorage);
        taskScheduler = new TaskScheduler(config);
        eventBus = new EventBus(taskScheduler);
        transportNetworkCache = new TransportNetworkCache(fileStorage, gtfsCache, osmCache);
        networkPreloader = new NetworkPreloader(transportNetworkCache);
        analysisWorker = new AnalysisWorker(fileStorage, networkPreloader, config);
    }

}
