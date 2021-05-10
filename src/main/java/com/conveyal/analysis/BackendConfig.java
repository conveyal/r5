package com.conveyal.analysis;

import com.conveyal.analysis.components.HttpApi;
import com.conveyal.analysis.components.LocalWorkerLauncher;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.controllers.OpportunityDatasetController;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/** Loads config information for the Analysis backend server and exposes it to the Components and HttpControllers. */
public class BackendConfig extends ConfigBase implements
        TaskScheduler.Config,
        AnalysisDB.Config,
        Broker.Config,
        HttpApi.Config,
        SeamlessCensusGridExtractor.Config,
        LocalWorkerLauncher.Config,
        LocalFileStorage.Config
{

    // CONSTANTS AND STATIC FIELDS

    private static final Logger LOG = LoggerFactory.getLogger(BackendConfig.class);
    public static final String BACKEND_CONFIG_FILE = "analysis.properties";

    // INSTANCE FIELDS

    private final boolean offline;
    private final String databaseName;
    private final String databaseUri;
    private final String localCacheDirectory;
    private final int serverPort;
    private final String seamlessCensusBucket;
    private final String seamlessCensusRegion;
    private final int lightThreads;
    private final int heavyThreads;
    private final int maxWorkers;
    // If set to true, the backend will start up and immediately exit with a success code.
    // This is used for testing that automated builds and JAR packaging are producing a usable artifact.
    public final boolean immediateShutdown;
    // For use in testing - setting this field will activate alternate code paths that cause intentional failures.
    private boolean testTaskRedelivery = false;

    // CONSTRUCTORS

    private BackendConfig (String filename) {
        this(propsFromFile(filename));
    }

    protected BackendConfig (Properties properties) {
        super(properties);
        // We intentionally don't supply any defaults here.
        // Any 'defaults' should be shipped in an example config file.
        immediateShutdown = boolProp("immediate-shutdown");
        databaseName = strProp("database-name");
        databaseUri = strProp("database-uri");
        localCacheDirectory = strProp("local-cache");
        serverPort = intProp("server-port");
        offline = boolProp("offline");
        seamlessCensusBucket = strProp("seamless-census-bucket");
        seamlessCensusRegion = strProp("seamless-census-region");
        lightThreads = intProp("light-threads");
        heavyThreads = intProp("heavy-threads");
        maxWorkers = intProp("max-workers");
        exitIfErrors();
    }

    // INTERFACE IMPLEMENTATIONS
    // Methods implementing Component and HttpController Config interfaces.
    // Note that one method can implement several Config interfaces at once.

    @Override public int     lightThreads()         { return lightThreads; }
    @Override public int     heavyThreads()         { return heavyThreads; }
    @Override public String  databaseUri()          { return databaseUri; }
    @Override public String  databaseName()         { return databaseName; }
    @Override public String  localCacheDirectory()  { return localCacheDirectory;}
    @Override public boolean testTaskRedelivery()   { return testTaskRedelivery; }
    @Override public String  seamlessCensusRegion() { return seamlessCensusRegion; }
    @Override public String  seamlessCensusBucket() { return seamlessCensusBucket; }
    @Override public int     serverPort()           { return serverPort; }
    @Override public boolean offline()              { return offline; }
    @Override public int     maxWorkers()           { return maxWorkers; }

    // STATIC FACTORY METHODS
    // Always use these to construct BackendConfig objects for readability.

    public static BackendConfig fromDefaultFile () {
        return new BackendConfig(BACKEND_CONFIG_FILE);
    }

}
