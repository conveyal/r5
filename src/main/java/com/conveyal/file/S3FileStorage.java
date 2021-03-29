package com.conveyal.file;

import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.PersistenceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * An implementation of FileStorage which persists immutable files to AWS S3 indefinitely into the future.
 * Most stored files are also mirrored "locally", which on AWS EC2 means EBS network-attached storage, because AWS EBS
 * is much faster than S3 and allows random access. This local mirror is ephemeral, only expected to survive until the
 * instance is terminated. Future deployments of the backend or workers will be able to re-mirror the same files from
 * S3. Files in the FileCategory TAUI are an exception and are not mirrored locally, only stored on S3.
 * In the S3-based implementation we need to set content type and compression details on S3, which we accomplish by
 * inspecting the "magic number" bytes at the beginning of the file and auto-setting the content type.
 */
public class S3FileStorage implements FileStorage {

    public interface Config extends LocalFileStorage.Config {
        String bucketPrefix();
    }

    private final AmazonS3 s3;

    private final LocalFileStorage localFileStorage;

    /**
     * This allows us to use different sets of buckets for different deployment environments.
     * For a given file category, the end of the bucket name is identical in all environments but this prefix changes.
     * TODO eventually this should be derived from the deployment-name config property (conveyal-{deployment-name})
     */
    private final String bucketPrefix;

    public S3FileStorage (Config config) {
        localFileStorage = new LocalFileStorage(config);
        s3 = AmazonS3ClientBuilder.defaultClient();
        bucketPrefix = config.bucketPrefix();
    }

    /** Map a FileStorageKey's category to an S3 bucket name. */
    private String bucket (FileStorageKey key) {
        return bucketPrefix + key.category.directoryName();
    }

    /** Move the file into S3 and then into local file storage. */
    @Override
    public void moveIntoStorage(FileStorageKey key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucket(key), key.path, file);
        if (FileUtils.isGzip(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            String contentType;
            try {
                // FIXME won't this return gzip every time? Or does it look inside gzip?
                contentType = Files.probeContentType(file.toPath());
            } catch (IOException e) {
                // TODO Log error here or fail entirely with exception?
                contentType = "application/octet-stream";
            }
            metadata.setContentType(contentType);
            metadata.setContentEncoding("gzip");
            putObjectRequest.withMetadata(metadata);
        }
        s3.putObject(putObjectRequest);
        // Move non-Taui files into the local disk storage.
        if (key.category != FileCategory.TAUI) {
            localFileStorage.moveIntoStorage(key, file);
        }
    }

    @Override
    public void moveIntoStorage (FileStorageKey fileStorageKey, PersistenceBuffer persistenceBuffer) {
        if (fileStorageKey.category != FileCategory.TAUI) {
            throw new IllegalArgumentException("Saving memory buffers is currently only used for Taui output.");
        }
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            // Set content encoding to gzip. This way browsers will decompress on download using native deflate code.
            // http://www.rightbrainnetworks.com/blog/serving-compressed-gzipped-static-files-from-amazon-s3-or-cloudfront/
            metadata.setContentEncoding("gzip");
            metadata.setContentType(persistenceBuffer.getMimeType());
            // We must setContentLength or the S3 client will re-buffer the InputStream into another memory buffer.
            metadata.setContentLength(persistenceBuffer.getSize());
            // The fluent PutObjectRequest.builder() doesn't seem to exist in our version of the SDK
            s3.putObject(bucket(fileStorageKey), fileStorageKey.path, persistenceBuffer.getInputStream(), metadata);
        } catch (SdkClientException e) {
            throw new RuntimeException("Upload of buffer to S3 failed.", e);
        }
    }

    @Override
    public File getFile(FileStorageKey key) {
        File localFile = localFileStorage.getFile(key);
        // A File object can represent a filesystem path for a file that doesn't exist yet, in which case we create it.
        if (!localFile.exists()) {
            // Before writing, ensure that the directory exists
            localFile.getParentFile().mkdirs();

            S3Object s3Object = s3.getObject(bucket(key), key.path);
            try {
                OutputStream fileOutputStream = FileUtils.getOutputStream(localFile);
                s3Object.getObjectContent().transferTo(fileOutputStream);
                fileOutputStream.close();
                s3Object.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return localFile;
    }

    @Override
    public String getURL (FileStorageKey key) {
        GeneratePresignedUrlRequest urlRequest = new GeneratePresignedUrlRequest(bucket(key), key.path)
                .withMethod(HttpMethod.GET)
                .withExpiration(Date.from(Instant.now().plus(1, ChronoUnit.WEEKS)));

        return s3.generatePresignedUrl(urlRequest).toString();
    }

    @Override
    public void delete(FileStorageKey key) {
        localFileStorage.delete(key);
        s3.deleteObject(bucket(key), key.path);
    }

    @Override
    public boolean exists(FileStorageKey key) {
        if (localFileStorage.exists(key)) {
            return true;
        }
        return s3.doesObjectExist(bucket(key), key.path);
    }

}
