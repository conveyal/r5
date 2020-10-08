package com.conveyal.analysis.components;

import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.file.FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Start workers as threads on the local machine.
 */
public class LocalWorkerLauncher implements WorkerLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(LocalWorkerLauncher.class);
    private static final int N_WORKERS_LOCAL = 1;
    private static final int N_WORKERS_LOCAL_TESTING = 4;

    public interface Config {
        String bundleBucket ();
        int serverPort ();
        String localCacheDirectory ();
        String gridBucket ();
        boolean testTaskRedelivery();
    }

    private final TransportNetworkCache transportNetworkCache;
    private final FileStorage fileStorage;

    private final Properties workerConfig = new Properties();
    private final int nWorkers;
    private final List<Thread> workerThreads = new ArrayList<>();

    public LocalWorkerLauncher (Config config, FileStorage fileStorage, GTFSCache gtfsCache, OSMCache osmCache) {
        LOG.info("Running in OFFLINE mode, a maximum of {} worker threads will be started locally.", N_WORKERS_LOCAL);
        this.fileStorage = fileStorage;
        transportNetworkCache = new TransportNetworkCache(
                fileStorage,
                gtfsCache,
                osmCache,
                config.bundleBucket()
        );
        // Create configuration for the locally running worker
        workerConfig.setProperty("work-offline", "true");
        // Do not auto-shutdown the local machine
        workerConfig.setProperty("auto-shutdown", "false");
        workerConfig.setProperty("broker-address", "localhost");
        workerConfig.setProperty("broker-port", Integer.toString(config.serverPort()));
        workerConfig.setProperty("cache-dir", config.localCacheDirectory());
        workerConfig.setProperty("pointsets-bucket", config.gridBucket());
        workerConfig.setProperty("aws-region", "eu-west-1"); // TODO remove? Should not be necessary with local worker.

        // From a throughput perspective there is no point in running more than one worker locally, since each worker
        // has at least as many threads as there are processor cores. But for testing purposes (e.g. testing that task
        // redelivery works right) we may want to start more workers to simulate running on a cluster.
        if (config.testTaskRedelivery()) {
            // When testing we want multiple workers. Below, all but one will have single point listening disabled
            // to allow them to run on the same machine without port conflicts.
            nWorkers = N_WORKERS_LOCAL_TESTING;
            // Tell the workers to return fake results, but fail part of the time.
            workerConfig.setProperty("test-task-redelivery", "true");
        } else {
            nWorkers = N_WORKERS_LOCAL;
        }

    }

    @Override
    public void launch (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot) {
        if (!workerThreads.isEmpty()) {
            LOG.error("Will not start additional workers, some are already running.");
            return;
        }
        int nTotal = nOnDemand + nSpot;
        LOG.info("Number of workers requested is {}.", nTotal);
        nTotal = nWorkers;
        LOG.info("Ignoring that and starting {} local Analysis workers...", nTotal);

        for (int i = 0; i < nTotal; i++) {
            Properties singleWorkerConfig = new Properties(workerConfig);
            // singleWorkerConfig.setProperty("initial-graph-id", category.graphId);
            // Avoid starting more than one worker on the same machine trying to listen on the same port.
            if (i > 0) {
                singleWorkerConfig.setProperty("listen-for-single-point", "false");
            }
            AnalysisWorker worker = new AnalysisWorker(singleWorkerConfig, fileStorage, transportNetworkCache);
            Thread workerThread = new Thread(worker, "WORKER " + i);
            workerThreads.add(workerThread);
            workerThread.start();
            // Note that machineId is static, so all workers have the same machine ID for now. This should be fixed somehow.
            LOG.info("Started worker {} with machine ID {}.", i, worker.machineId);
        }
    }

}
