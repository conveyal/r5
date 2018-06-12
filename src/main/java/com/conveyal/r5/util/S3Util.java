package com.conveyal.r5.util;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by matthewc on 10/21/16.
 */
public class S3Util {
    private static final Logger LOG = LoggerFactory.getLogger(S3Util.class);
    public static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 256, 60,TimeUnit.SECONDS, new ArrayBlockingQueue<>(255));
    // can't use CallerRunsPolicy as that would cause deadlocks, calling thread is writing to inputstream
    static {
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    }

    public static void streamToS3 (String bucket, String key, InputStream is, ObjectMetadata metadata) {
        // write to S3 in a thread
        executor.execute(() -> {
            try {
                s3.putObject(bucket, key, is, metadata);
                is.close();
            } catch (Exception e) {
                LOG.error("Exception writing to S3", e);
            }
        });
    }
}
