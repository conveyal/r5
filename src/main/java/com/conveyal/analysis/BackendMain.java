package com.conveyal.analysis;

import com.conveyal.analysis.components.BackendComponents;
import com.conveyal.analysis.components.LocalBackendComponents;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.util.ExceptionUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the main entry point for starting a Conveyal Analysis server.
 */
public abstract class BackendMain {

    private static final Logger LOG = LoggerFactory.getLogger(BackendMain.class);

    public static void main (String... args) {
        final BackendComponents components = new LocalBackendComponents();
        startServer(components);
    }

    protected static void startServer (BackendComponents components, Thread... postStartupThreads) {
        // We have several non-daemon background thread pools which will keep the JVM alive if the main thread crashes.
        // If initialization fails, we need to catch the exception or error and force JVM shutdown.
        try {
            startServerInternal(components, postStartupThreads);
        } catch (Throwable throwable) {
            LOG.error("Exception while starting up backend, shutting down JVM.\n{}", ExceptionUtils.stackTraceString(throwable));
            System.exit(1);
        }
    }

    private static void startServerInternal (BackendComponents components, Thread... postStartupThreads) {
        LOG.info("Starting Conveyal analysis backend, the time is now {}", DateTime.now());
        LOG.info("Backend version is: {}", BackendVersion.instance.version);
        LOG.info("Connecting to database...");

        // Persistence, the census extractor, and ApiMain are initialized statically, without creating instances,
        // passing in non-static components we've already created. TODO migrate to non-static Components.
        // TODO remove the static ApiMain abstraction layer. We do not use it anywhere but in handling GraphQL queries.
        // TODO we could move this to something like BackendComponents.initialize()
        Persistence.initializeStatically(components.config);
        SeamlessCensusGridExtractor.configureStatically(components.config);
        ApiMain.initialize(components.gtfsCache);
        PointSetCache.initializeStatically(components.fileStorage);

        if (components.config.offline()) {
            LOG.info("Running in OFFLINE mode.");
            LOG.info("Pre-starting local cluster of Analysis workers...");
            components.workerLauncher.launch(new WorkerCategory(null, null), null, 1, 0);
        }

        LOG.info("Conveyal Analysis server is ready.");
        // TODO replace postStartupThreads with something like components.taskScheduler.enqueueHeavyTask();
        for (Thread thread : postStartupThreads) {
            thread.start();
        }

        if (components.config.immediateShutdown) {
            LOG.info("Startup has completed successfully. Exiting immediately as requested.");
            System.exit(0);
        }
    }

}
