package com.conveyal.analysis;

import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.file.FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.OSMCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/** Loads config information for an analysis worker and exposes it to the worker's Components and HttpControllers. */
public class WorkerConfig extends ConfigBase implements
        TaskScheduler.Config,
        FileStorage.Config,
        AnalysisWorker.Config
{

    // CONSTANTS

    private static final Logger LOG = LoggerFactory.getLogger(ConfigBase.class);
    private static final String WORKER_CONFIG_FILE = "worker.conf";

    // INSTANCE FIELDS

    private final boolean workOffline;
    private final String  awsRegion; // This shouldn't be needed on recent AWS SDKs, eventually eliminate it.
    private final String  bucketPrefix;
    private final String  initialGraphId;
    private final String  cacheDirectory;
    private final String  brokerAddress;
    private final String  brokerPort;
    private final boolean autoShutdown;
    private final int     lightThreads;
    private final int     heavyThreads;
    private final boolean testTaskRedelivery;
    private final boolean listenForSinglePoint;

    // CONSTRUCTORS

    private WorkerConfig (String filename) {
        this(propsFromFile(filename));
    }

    private WorkerConfig (Properties props) {
        super(props);
        workOffline = boolProp("work-offline");
        if (workOffline) {
            // Candidates for separate offline worker config - but might not be worth it for 2 options.
            awsRegion = initialGraphId = null;
            bucketPrefix = null;
        } else {
            awsRegion = strProp("aws-region");
            initialGraphId = strProp("initial-graph-id");
            bucketPrefix = strProp("bucket-prefix");
        }
        // These bucket names are used even in local operation as cache subdirectories.
        cacheDirectory = strProp("cache-dir");
        brokerAddress = strProp("broker-address");
        brokerPort = strProp("broker-port");
        autoShutdown = boolProp("auto-shutdown");
        {
            // Should we supply these in properties, or should this be done elsewhere?
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            LOG.info("Java reports the number of available processors is: {}", availableProcessors);
            lightThreads = availableProcessors;
            heavyThreads = availableProcessors;
        }
        testTaskRedelivery = boolProp("test-task-redelivery");
        listenForSinglePoint = boolProp("listen-for-single-point");
        exitIfErrors();
    }

    // INTERFACE IMPLEMENTATIONS
    // Methods implementing Component and HttpController Config interfaces.
    // Note that one method can implement several Config interfaces at once.

    @Override public boolean workOffline()     { return workOffline; }
    @Override public String  awsRegion()       { return awsRegion; }
    @Override public String  bucketPrefix()    { return bucketPrefix; }
    @Override public String  initialGraphId()  { return initialGraphId; }
    @Override public String localCacheDirectory ()  { return cacheDirectory; }
    @Override public String  brokerAddress()   { return brokerAddress; }
    @Override public String  brokerPort()      { return brokerPort; }
    @Override public boolean autoShutdown()    { return autoShutdown; }
    @Override public int     lightThreads ()   { return lightThreads; }
    @Override public int     heavyThreads ()   { return heavyThreads; }
    @Override public boolean testTaskRedelivery()   { return testTaskRedelivery; }
    @Override public boolean listenForSinglePoint() { return listenForSinglePoint; }

    // STATIC FACTORY METHODS
    // Use these to construct WorkerConfig objects for readability.

    public static WorkerConfig fromDefaultFile () {
        return new WorkerConfig(WORKER_CONFIG_FILE);
    }

    public static WorkerConfig fromProperties (Properties properties) {
        return new WorkerConfig(properties);
    }

}
