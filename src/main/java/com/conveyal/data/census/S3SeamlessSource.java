package com.conveyal.data.census;

import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.RequestPayer;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;

/**
 * A seamless data source based on storage in Amazon S3.
 */
public class S3SeamlessSource extends SeamlessSource {
    private static S3Client s3;

    public final String region;
    public final String bucketName;

    public S3SeamlessSource(String bucketName) {
        this.region = null;
        this.bucketName = bucketName;
        this.s3 = S3Client.create();
    }

    public S3SeamlessSource(String region, String bucketName) {
        this.region = region;
        this.bucketName = bucketName;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    protected InputStream getInputStream(int x, int y) throws IOException {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(String.format("%d/%d.pbf.gz", x, y))
                    .requestPayer(RequestPayer.REQUESTER) // the LODES bucket is requester-pays.
                    .build();
            return s3.getObject(req, ResponseTransformer.toInputStream());
        } catch (S3Exception e) {
            // there is no data in this tile
            if ("NoSuchKey".equals(e.awsErrorDetails().errorCode()))
                return null;
            else
                // re-throw, something else is amiss
                throw e;
        }
    }
}
