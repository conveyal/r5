package com.conveyal.analysis;

import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/** Loads config information for an analysis worker and exposes it to the worker's Components and HttpControllers. */
public abstract class WorkerConfig extends ConfigBase implements TaskScheduler.Config, AnalysisWorker.Config {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigBase.class);

    // INSTANCE FIELDS

    private final String  brokerAddress;
    private final String  brokerPort;
    private final int     lightThreads;
    private final int     heavyThreads;
    private final boolean testTaskRedelivery;
    private final boolean listenForSinglePoint;

    // CONSTRUCTORS

    protected WorkerConfig (Properties props) {
        super(props);
        brokerAddress = strProp("broker-address");
        brokerPort = strProp("broker-port");
        {
            // Should we supply these in properties, or should this be inferred from CPU cores elsewhere?
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            LOG.info("Java reports the number of available processors is: {}", availableProcessors);
            lightThreads = availableProcessors;
            heavyThreads = availableProcessors;
        }
        testTaskRedelivery = boolProp("test-task-redelivery");
        listenForSinglePoint = boolProp("listen-for-single-point");
        // No call to exitIfErrors() here, that should be done in concrete subclasses.
    }

    // INTERFACE IMPLEMENTATIONS
    // Methods implementing Component and HttpController Config interfaces.
    // Note that one method can implement several Config interfaces at once.

    @Override public String  brokerAddress()   { return brokerAddress; }
    @Override public String  brokerPort()      { return brokerPort; }
    @Override public int     lightThreads ()   { return lightThreads; }
    @Override public int     heavyThreads ()   { return heavyThreads; }
    @Override public boolean testTaskRedelivery()   { return testTaskRedelivery; }
    @Override public boolean listenForSinglePoint() { return listenForSinglePoint; }

}
