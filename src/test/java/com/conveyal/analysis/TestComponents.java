package com.conveyal.analysis;

import com.conveyal.analysis.components.Components;
import com.conveyal.analysis.components.HttpApi;
import com.conveyal.analysis.components.LocalAuthentication;
import com.conveyal.analysis.components.LocalWorkerLauncher;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.streets.OSMCache;

import java.io.File;
import java.nio.file.Files;

import static com.conveyal.analysis.components.LocalComponents.standardHttpControllers;

/**
 * Created by abyrd on 2020-05-24
 */
public class TestComponents extends Components {

    public TestComponents () {
        try {
            File tempDirectory = Files.createTempDirectory(null).toFile();
            tempDirectory.deleteOnExit();
            config = new BackendConfig("analysis.properties.test");
            taskScheduler = new TaskScheduler(config);
            fileStorage = new LocalFileStorage(tempDirectory.getAbsolutePath());
            osmCache = new OSMCache(fileStorage, () -> "osm");
            gtfsCache = new GTFSCache(fileStorage, () -> "gtfs");
            workerLauncher = new LocalWorkerLauncher(config, fileStorage, gtfsCache, osmCache);
            database = new AnalysisDB(config);
            eventBus = new EventBus(taskScheduler);
            broker = new Broker(config, fileStorage, eventBus, workerLauncher);
            authentication = new LocalAuthentication();
            httpApi = new HttpApi(fileStorage, authentication, config, standardHttpControllers(this));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to wire up test components.", ex);
        }
    }

}
