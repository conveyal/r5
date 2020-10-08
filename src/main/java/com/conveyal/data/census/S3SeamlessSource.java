package com.conveyal.data.census;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * A seamless data source based on storage in Amazon S3.
 */
public class S3SeamlessSource extends SeamlessSource {
    private static AmazonS3 s3;

    public final String region;
    public final String bucketName;

    public S3SeamlessSource(String bucketName) {
        this.region = null;
        this.bucketName = bucketName;
        this.s3 = AmazonS3ClientBuilder.defaultClient();
    }

    public S3SeamlessSource(String region, String bucketName) {
        this.region = region;
        this.bucketName = bucketName;
        this.s3 = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .build();
    }

    @Override
    protected InputStream getInputStream(int x, int y) throws IOException {
        try {
            GetObjectRequest req = new GetObjectRequest(bucketName, String.format("%d/%d.pbf.gz", x, y));
            // the LODES bucket is requester-pays.
            req.setRequesterPays(true);
            return s3.getObject(req).getObjectContent();
        } catch (AmazonS3Exception e) {
            // there is no data in this tile
            if ("NoSuchKey".equals(e.getErrorCode()))
                return null;
            else
                // re-throw, something else is amiss
                throw e;
        }
    }
}
