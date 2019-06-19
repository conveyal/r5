package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

/**
 * Cache opportunity density grids on S3, with a local cache for performance when reusing the same grids. Each instance
 * works with a single S3 bucket.
 */
public class PointSetCache {
    private static final Logger LOG = LoggerFactory.getLogger(PointSetCache.class);

    private AmazonS3 s3 = null;

    /** How large the cache should be. Should be large enough to fit all field of a project */
    private static final int CACHE_SIZE = 200;

    private LoadingCache<String, PointSet> cache = CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                // lambdas not legal here for whatever reason
                .build(new CacheLoader<String, PointSet>() {
                    @Override
                    public PointSet load(String s) throws Exception {
                        return loadGrid(s);
                    }
                });

    private final String region;
    private final String bucket;

    public PointSetCache(String region, String bucket) {
        this.bucket = bucket;
        this.region = region;
        s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();
    }

    private PointSet loadGrid (String key) throws IOException {
        S3Object obj = s3.getObject(bucket, key);
        // no need to check if it exists; if it doesn't getObject will throw an exception which will be caught in the
        // get function below
        // Grids are gzipped on S3
        InputStream is = new GZIPInputStream(new BufferedInputStream(obj.getObjectContent()));

        String extension = key.substring(key.lastIndexOf(".") + 1);

        if ("grid".equalsIgnoreCase(extension)){ //TODO use format enum
            return Grid.read(is);
        } else if ("csv".equalsIgnoreCase(extension)) {
            Files.copy(is, Paths.get("pointsets/" + key)); // TODO stream instead of writing/reading file
            return FreeFormPointSet.fromCsv(new File("pointsets" + key));
        } else if ("geojson".equalsIgnoreCase(extension)){
            Files.copy(is, Paths.get("pointsets/" + key)); // TODO stream instead of writing/reading file
            return FreeFormPointSet.fromGeoJson(new File("pointsets" + key));
        } else {
            throw new UnsupportedOperationException("Unsupported pointset format");
        }


    }

    public PointSet get (String key) {
        try {
            return cache.get(key);
        } catch (ExecutionException e) {
            LOG.error("Error retrieving grid {}", key, e);
            throw new RuntimeException(e);
        }
    }
}
