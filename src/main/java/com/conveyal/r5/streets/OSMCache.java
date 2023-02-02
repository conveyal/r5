package com.conveyal.r5.streets;

import com.conveyal.analysis.components.Component;
import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OsmLibException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * TODO this should be moved so we don't need to have a dependency on AWS S3 SDK and multiple S3 clients.
 * Maybe a general local+S3 object storage mechanism for externalizable objects, using the TransferManager.
 */
public class OSMCache implements Component {

    private final FileStorage fileStorage;

    /**
     * Construct a new OSMCache.
     * If bucket is null, we will work offline (will not create an S3 client, avoiding need to set an AWS region).
     */
    public OSMCache (FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private Cache<String, OSM> osmCache = CacheBuilder.newBuilder()
            .maximumSize(10)
            .build();

    public static String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9]", "-");
    }

    public static FileStorageKey getKey (String id) {
        // FIXME Transforming IDs each time they're used seems problematic. They should probably only be validated here.
        String cleanId = cleanId(id);
        return new FileStorageKey(FileCategory.BUNDLES, cleanId + ".pbf");
    }

    /** This should always return an OSM object, not null. If something prevents that, it should throw an exception. */
    public @Nonnull OSM get (String id) throws OsmLibException {
        try {
            return osmCache.get(id, () -> {
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
