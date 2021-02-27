package com.conveyal.r5.streets;

import com.conveyal.file.Bucket;
import com.conveyal.osmlib.OSM;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * TODO this should be moved out of osm-lib and R5 into Analysis, and better integrated with TransportNetworkCache.
 */
public class OSMCache {
    public final Bucket bucket;

    /**
     * Construct a new OSMCache.
     * If bucket is null, we will work offline (will not create an S3 client, avoiding need to set an AWS region).
     */
    public OSMCache (Bucket bucket) {
        this.bucket = bucket;
    }

    private Cache<String, OSM> osmCache = CacheBuilder.newBuilder()
            .maximumSize(10)
            .build();

    public String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9]", "-");
    }

    public String getKey (String id) {
        return cleanId(id) + ".pbf";
    }

    public OSM get (String id) {
        try {
            return osmCache.get(id, () -> {
                File osmFile = bucket.getFile(getKey(id));
                OSM ret = new OSM(null);
                ret.intersectionDetection = true;
                ret.readFromFile(osmFile.getAbsolutePath());
                return ret;
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
