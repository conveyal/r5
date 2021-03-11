package com.conveyal.analysis;

import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;

import java.io.FileReader;
import java.io.Reader;
import java.util.Properties;

/**
 * This holds the configuration options read in from the worker.conf properties file. It then exposes the configuration
 * interfaces for all the components used by a worker. Multiple component configuration interfaces may reuse the same
 * methods. Some validation could be performed here, but interpretation or conditional logic should be provided in
 * Components themselves, or in alternate WorkerComponents implementations.
 *
 * Since we always supply a machine-generated config file to the worker, we should avoid having any defaults applied
 * here (which would create multiple levels of defaults interacting with each other).
 *
 * However, we currently always supply the same worker.conf independent of whether we're on the cluster or running
 * offline locally. So we will proceed incrementally in removing or relocating conditional logic.
 */
public class WorkerConfig implements TaskScheduler.Config, OSMCache.Config, GTFSCache.Config, AnalysisWorker.Config {

    public static final String DEFAULT_CONFIG_FILE = "worker.conf";

    public final boolean workOffline;
    public final String cacheDirectory;
    public final String graphsBucket;
    public final String awsRegion; // This shouldn't be needed on recent AWS SDKs, eventually eliminate it.

    public final String baseBucket;
    public final boolean testTaskRedelivery;
    public final String brokerAddress;
    public final String brokerPort;

    public final String pointsetsBucket;
    public final boolean autoShutdown;
    public final boolean listenForSinglePoint;

    public final String initialGraphId;

    private final int lightThreads;
    private final int heavyThreads;

    public static WorkerConfig load () {
        return fromPropertiesFile(DEFAULT_CONFIG_FILE);
    }

    private static WorkerConfig fromPropertiesFile (String filename) {
        try (Reader propsReader = new FileReader(filename)) {
            Properties config = new Properties();
            config.load(propsReader);
            return new WorkerConfig(config);
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration properties.", e);
        }
    }

    public WorkerConfig (Properties config) {
        workOffline = Boolean.parseBoolean(config.getProperty("work-offline"));
        cacheDirectory = config.getProperty("cache-dir");
        graphsBucket = workOffline ? null : config.getProperty("graphs-bucket");
        awsRegion = workOffline ? null : config.getProperty("aws-region");

        // TODO method to require parameters and parse numeric ones, shared with BackendConfig via superclass
        baseBucket = config.getProperty("base-bucket");
        testTaskRedelivery = Boolean.parseBoolean(config.getProperty("test-task-redelivery"));
        brokerAddress = config.getProperty("broker-address");
        brokerPort = config.getProperty("broker-port");

        pointsetsBucket = config.getProperty("pointsets-bucket");
        autoShutdown = Boolean.parseBoolean(config.getProperty("auto-shutdown", "false"));
        listenForSinglePoint = Boolean.parseBoolean(config.getProperty("listen-for-single-point", "true"));

        initialGraphId = config.getProperty("initial-graph-id");

        lightThreads = Integer.parseInt(config.getProperty("light-threads"));
        heavyThreads = Integer.parseInt(config.getProperty("heavy-threads"));
    }

    // Implementations of Component and HttpController Config interfaces
    // Note that one method can implement several Config interfaces at once.

    // TaskScheduler Config Interface

    @Override public int lightThreads () { return lightThreads; }
    @Override public int heavyThreads () { return heavyThreads; }

    // OSM abd GTFS cache config (eventually revise for consistent naming of method and variable)

    @Override public String bundleBucket() { return graphsBucket; }

    // AnalysisWorker Configuration Interface

    @Override public boolean workOffline () { return workOffline; }
    @Override public boolean testTaskRedelivery () { return testTaskRedelivery; }
    @Override public String cacheDirectory () { return cacheDirectory; }
    @Override public String graphsBucket () { return graphsBucket; }
    @Override public String awsRegion () { return awsRegion; } // This shouldn't be needed on recent AWS SDKs, eventually eliminate it
    @Override public String baseBucket () { return baseBucket; }
    @Override public String brokerAddress () { return brokerAddress; }
    @Override public String brokerPort () { return brokerPort; }
    @Override public String pointsetsBucket () { return pointsetsBucket; }
    @Override public boolean autoShutdown () { return autoShutdown; }
    @Override public boolean listenForSinglePoint () { return listenForSinglePoint; }
    @Override public String initialGraphId () { return initialGraphId; }


}
