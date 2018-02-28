package com.conveyal.r5.multipoint;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.google.common.io.LittleEndianDataOutputStream;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Store static site publication data in S3.
 */
public abstract class MultipointDataStore {
    private static AmazonS3 s3 = AmazonS3Client.builder().build();

    /**
     * Get an output stream to upload an object to S3 for the given static site request.
     * There is no need to gzip data going into this stream, it will be gzipped on upload in storage and when downloaded
     */
    public static LittleEndianDataOutputStream getOutputStream (AnalysisTask req, String filename, String type) throws IOException {
        ObjectMetadata md = new ObjectMetadata();
        // This way the browser will decompress on download
        // http://www.rightbrainnetworks.com/blog/serving-compressed-gzipped-static-files-from-amazon-s3-or-cloudfront/
        md.setContentEncoding("gzip");
        md.setContentType(type);

        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);

        PutObjectRequest por = new PutObjectRequest("analysis-static/" + req.jobId, filename, pipeIn, md);
        // buffer files up to 100MB
        por.getRequestClientOptions().setReadLimit(100 * 1024 * 1024 + 1);

        // run s3 put in background thread so pipedinputstream does not block.
        // TODO gzip in background as well?
        new Thread(() -> {
            s3.putObject(por);
        }).start();

        return new LittleEndianDataOutputStream(new GZIPOutputStream(pipeOut));
    }
}