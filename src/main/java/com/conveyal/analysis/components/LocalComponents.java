package com.conveyal.analysis.components;

import com.conveyal.analysis.BackendConfig;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.controllers.AggregationAreaController;
import com.conveyal.analysis.controllers.BrokerController;
import com.conveyal.analysis.controllers.BundleController;
import com.conveyal.analysis.controllers.FileStorageController;
import com.conveyal.analysis.controllers.GTFSGraphQLController;
import com.conveyal.analysis.controllers.HttpController;
import com.conveyal.analysis.controllers.ModificationController;
import com.conveyal.analysis.controllers.OpportunityDatasetController;
import com.conveyal.analysis.controllers.ProjectController;
import com.conveyal.analysis.controllers.RegionalAnalysisController;
import com.conveyal.analysis.controllers.TimetableController;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.streets.OSMCache;

import java.util.Arrays;
import java.util.List;

/**
 * Wires up the components for a local backend instance (as opposed to a cloud cluster).
 */
public class LocalComponents extends Components {

    public LocalComponents () {
        config = new BackendConfig();
        taskScheduler = new TaskScheduler(config);
        fileStorage = new LocalFileStorage(
                config.localCacheDirectory(),
                String.format("http://localhost:%s/files", config.serverPort())
        );
        gtfsCache = new GTFSCache(fileStorage, config);
        osmCache = new OSMCache(fileStorage, config);
        // New (October 2019) DB layer, this should progressively replace the Persistence class
        database = new AnalysisDB(config);
        eventBus = new EventBus(taskScheduler);
        authentication = new LocalAuthentication();
        workerLauncher = new LocalWorkerLauncher(config, fileStorage, gtfsCache, osmCache);
        broker = new Broker(config, fileStorage, eventBus, workerLauncher);
        // Instantiate the HttpControllers last, when all the components except the HttpApi are already created.
        httpApi = new HttpApi(fileStorage, authentication, config, standardHttpControllers(this));
        // compute = new LocalCompute();
        // persistence = persistence(local_Mongo)
    }

    /**
     * Create the standard list of HttpControllers used in local operation.
     * The Components parameter should already be initialized with all components except the HttpApi.
     * We pass these controllers into the HttpApi (rather than constructing them in the HttpApi constructor) to allow
     * injecting custom controllers in other deployment environments. This also avoids bulk-passing the entire set
     * of components into the HttpApi constructor, ensuring clear declaration of each component's dependencies.
     * Such bulk-passing of components should only occur in this wiring-up code, not in component code.
     */
     public static List<HttpController> standardHttpControllers (Components components) {
        final List<HttpController> httpControllers = Arrays.asList(
                // These handlers are at paths beginning with /api
                // and therefore subject to authentication and authorization.
                new ModificationController(),
                new ProjectController(),
                new GTFSGraphQLController(components.gtfsCache),
                new BundleController(components),
                new OpportunityDatasetController(components.fileStorage, components.taskScheduler, components.config),
                new RegionalAnalysisController(components.broker, components.fileStorage, components.config),
                new AggregationAreaController(components.fileStorage, components.config),
                new TimetableController(),
                new FileStorageController(components.fileStorage, components.database),
                // This broker controller registers at least one handler at URL paths beginning with /internal, which
                // is exempted from authentication and authorization, but should be hidden from the world
                // outside the cluster by the reverse proxy. Perhaps we should serve /internal on a separate
                // port so they can't be accidentally exposed by the reverse proxy. It could even be a separate
                // InternalHttpApi component with its own spark service, renaming this ExternalHttpApi.
                new BrokerController(components.broker, components.eventBus)
        );
        return httpControllers;
    }

}
