package com.conveyal.analysis.components;

import com.conveyal.analysis.BackendConfig;
import com.conveyal.analysis.WorkerConfig;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.FilePersistence;
import com.conveyal.r5.analyst.NetworkPreloader;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unlike BackendComponents this is not abstract because we don't expect to have multiple alternative wirings-up.
 * It might make sense to eventually have a local and cluster WorkerComponents though.
 */
public class WorkerComponents {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerComponents.class);

    public WorkerConfig config;

    public FileStorage fileStorage;

    public AnalysisWorker analysisWorker; // TODO rename to AnalystWorker in subsequent commit

    // TODO make non-static and make implementations swappable, merge with FileStorage?
    public FilePersistence filePersistence;

    /** Keeps some TransportNetworks around, lazy-loading or lazy-building them. */
    public NetworkPreloader networkPreloader;


    // We could actually read things like scenarios or even job descriptions directly from the database.
    // public AnalysisDB database;

    // This will be used for regularly recurring status reports to the backend and any asynchronous tasks.
    public TaskScheduler taskScheduler;

    public EventBus eventBus;

    public WorkerComponents () {
        this.config = WorkerConfig.load();
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
        this.eventBus = eventBus;
    }

}
