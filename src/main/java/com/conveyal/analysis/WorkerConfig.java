package com.conveyal.analysis;

import com.conveyal.file.FileStorage;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.file.S3FileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.streets.OSMCache;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Properties;

public class WorkerConfig {

    public static final String DEFAULT_CONFIG_FILE = "worker.conf";

    public final boolean workOffline;
    public final String graphDirectory;
    public final FileStorage fileStore;
    public final String graphsBucket;
    public final OSMCache osmCache;
    public final GTFSCache gtfsCache;

    public static WorkerConfig load () {
        return fromPropertiesFile(DEFAULT_CONFIG_FILE);
    }

    private static WorkerConfig fromPropertiesFile (String filename) {
        Properties config = new Properties();
        try (Reader propsReader = new FileReader(filename)) {
            return new WorkerConfig(config);
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration properties.", e);
        }
    }

    private WorkerConfig (Properties config) {

        workOffline = Boolean.parseBoolean(config.getProperty("work-offline", "false"));
        graphDirectory = config.getProperty("cache-dir", "cache/graphs");
        if (workOffline) {
            fileStore = new LocalFileStorage(graphDirectory);
        } else {
            fileStore = new S3FileStorage(config.getProperty("aws-region"), graphDirectory);
        }
        graphsBucket = workOffline ? null : config.getProperty("graphs-bucket");
        osmCache = new OSMCache(fileStore, () -> graphsBucket);
        gtfsCache = new GTFSCache(fileStore, () -> graphsBucket);
    }


}
