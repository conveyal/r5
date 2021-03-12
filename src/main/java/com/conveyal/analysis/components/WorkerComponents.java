package com.conveyal.analysis.components;

import com.conveyal.analysis.WorkerConfig;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.file.FileStorage;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.file.S3FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.FilePersistence;
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

    public final WorkerConfig config;

    public final FileStorage fileStorage;

    // We could actually read things like scenarios or even job descriptions directly from the database.
    // Currently workers never contact the database themselves (that would add hundreds or thousands of db connections).
    // public AnalysisDB database;

    // This will be used for regularly recurring status reports to the backend and any asynchronous tasks.
    public final TaskScheduler taskScheduler;

    public final EventBus eventBus;

    public final OSMCache osmCache;

    public final GTFSCache gtfsCache;

    public final TransportNetworkCache transportNetworkCache;

    // TODO rename to AnalystWorker in subsequent commit
    public final AnalysisWorker analysisWorker;

    // This should eventually be merged with FileStorage, which mostly serves the same purpose.
    // Although we should rethink the idea of PersistenceBuffers at the same time (which provide buffering and
    // compression so file size is known before uploads, as required by some HTTP APIs).
    public final FilePersistence filePersistence;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    public final NetworkPreloader networkPreloader;

    public WorkerComponents () {
        this(WorkerConfig.fromDefaultFile());
    }

    public WorkerComponents (WorkerConfig config) {
        this.config = config;
        // Eventually, conditional wiring may be replaced with polymorphism (subclasses) and Config interfaces.
        if (config.workOffline()) {
            fileStorage = new LocalFileStorage(config.cacheDirectory());
        } else {
            fileStorage = new S3FileStorage(config.awsRegion(), config.cacheDirectory());
        }
        osmCache = new OSMCache(fileStorage, config);
        gtfsCache = new GTFSCache(fileStorage, config);
        taskScheduler = new TaskScheduler(config);
        eventBus = new EventBus(taskScheduler);
        filePersistence = null;
        networkPreloader = null;
        transportNetworkCache = new TransportNetworkCache(fileStorage, gtfsCache, osmCache, config.bundleBucket());
        analysisWorker = new AnalysisWorker(fileStorage, transportNetworkCache, networkPreloader, config);
    }

}
