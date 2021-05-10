package com.conveyal.analysis;

import com.conveyal.file.LocalFileStorage;

import java.util.Properties;

/**
 * Note that some local config is not supplied by config files,
 * e.g. the initial graph is hard-wired to null in local operation, and files are always served on the same port.
 */
public class LocalWorkerConfig extends WorkerConfig implements LocalFileStorage.Config {

    private final String  cacheDirectory;

    private LocalWorkerConfig (Properties props) {
        super(props);
        // Actually this is not used directly, backend storage component is passed in to local worker constructor.
        cacheDirectory = strProp("cache-dir");
        exitIfErrors();
    }

    // INTERFACE IMPLEMENTATIONS
    // Methods implementing Component and HttpController Config interfaces.
    // Note that one method can implement several Config interfaces at once.

    // FIXME align with actual local file serving port, somehow connected to API?
    @Override public int     serverPort() { return -1; }
    @Override public String  localCacheDirectory () { return cacheDirectory; }
    @Override public String  initialGraphId () { return null; } //

    // STATIC FACTORY METHODS
    // Use these to construct WorkerConfig objects for readability.

    public static LocalWorkerConfig fromProperties (Properties properties) {
        return new LocalWorkerConfig(properties);
    }

}
