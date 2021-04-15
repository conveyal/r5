package com.conveyal.r5.streets;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OsmLibException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.File;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;

/**
 * TODO this should be moved so we don't need to have a dependency on AWS S3 SDK and multiple S3
 * clients. Maybe a general local+S3 object storage mechanism for externalizable objects, using the
 * TransferManager.
 */
public class OSMCache {

    private final String bucket;
    private final FileStorage fileStorage;

    public interface Config {
        String bundleBucket();
    }

    /**
     * Construct a new OSMCache. If bucket is null, we will work offline (will not create an S3
     * client, avoiding need to set an AWS region).
     */
    public OSMCache(FileStorage fileStorage, Config config) {
        this.bucket = config.bundleBucket();
        this.fileStorage = fileStorage;
    }

    private Cache<String, OSM> osmCache = CacheBuilder.newBuilder().maximumSize(10).build();

    public String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9]", "-");
    }

    public FileStorageKey getKey(String id) {
        // FIXME Transforming IDs each time they're used seems problematic. They should probably
        // only be validated here.
        String cleanId = cleanId(id);
        return new FileStorageKey(bucket, cleanId + ".pbf");
    }

    /**
     * This should always return an OSM object, not null. If something prevents that, it should
     * throw an exception.
     */
    public @Nonnull OSM get(String id) throws OsmLibException {
        try {
            return osmCache.get(
                    id,
                    () -> {
                        File osmFile = fileStorage.getFile(getKey(id));
                        OSM ret = new OSM(null);
                        ret.intersectionDetection = true;
                        ret.readFromFile(osmFile.getAbsolutePath());
                        return ret;
                    });
        } catch (ExecutionException e) {
            throw new OsmLibException("Exception in OSM MapDB CacheLoader.", e.getCause());
        }
    }
}
