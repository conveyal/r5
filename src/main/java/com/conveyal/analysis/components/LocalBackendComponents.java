package com.conveyal.analysis.components;

import com.conveyal.analysis.BackendConfig;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.ErrorLogger;
import com.conveyal.analysis.controllers.LocalFilesController;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.data.census.SeamlessSource;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.components.TaskScheduler;
import com.conveyal.r5.components.eventbus.EventBus;
import com.conveyal.r5.controllers.HttpController;
import com.conveyal.r5.streets.OSMCache;

import java.io.InputStream;
import java.util.List;

/**
 * Wires up the components for a local backend instance (as opposed to a cloud-hosted backend instance).
 * This establishes the implementations and dependencies between them, and supplies configuration.
 * No conditional logic should be present here.
 * Differences in implementation or configuration are handled by the Components themselves.
 */
public class LocalBackendComponents extends BackendComponents {

    public LocalBackendComponents () {
        config = BackendConfig.fromDefaultFile();
        taskScheduler = new TaskScheduler(config);
        fileStorage = new LocalFileStorage(config);
        gtfsCache = new GTFSCache(fileStorage);
        osmCache = new OSMCache(fileStorage);
        // New (October 2019) DB layer, this should progressively replace the Persistence class
        database = new AnalysisDB(config);
        eventBus = new EventBus(taskScheduler);
        authentication = new LocalAuthentication();
        // TODO add nested LocalWorkerComponents here, to reuse some components, and pass it into the LocalWorkerLauncher?
        workerLauncher = new LocalWorkerLauncher(config, fileStorage, gtfsCache, osmCache);
        broker = new Broker(config, fileStorage, eventBus, workerLauncher);
        // Seamless census data is stored on S3, which is only accessible from the cluster backend.
        SeamlessSource censusSource = new SeamlessSource() {
            @Override
            protected InputStream getInputStream (int x, int y) {
                throw new UnsupportedOperationException("Seamless census extraction requires the cluster backend.");
            }
        };
        censusExtractor = new SeamlessCensusGridExtractor(censusSource, config.seamlessCensusBucket());
        // Instantiate the HttpControllers last, when all the components except the HttpApi are already created.
        List<HttpController> httpControllers = standardHttpControllers();
        httpControllers.add(new LocalFilesController(fileStorage));
        httpApi = new HttpApi(fileStorage, authentication, eventBus, config, httpControllers);
        // compute = new LocalCompute();
        // persistence = persistence(local_Mongo)
        eventBus.addHandlers(new ErrorLogger());
    }

}
