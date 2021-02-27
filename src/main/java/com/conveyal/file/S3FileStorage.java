package com.conveyal.file;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Date;

public class S3FileStorage implements FileStorage {

    private final AmazonS3 s3;

    private final LocalFileStorage localFileStorage;

    public S3FileStorage (String region, String localCacheDirectory) {
        localFileStorage = new LocalFileStorage(localCacheDirectory);
        s3 = AmazonS3ClientBuilder.standard().withRegion(region).build();
    }

    /**
     * Move the file into S3 and then into local file storage.
     */
    public void moveIntoStorage(FileStorageKey key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(key.bucket, key.path, file);
        if (FileUtils.isGzip(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            String contentType;
            try {
                contentType = Files.probeContentType(file.toPath());
            } catch (IOException e) {
                // TODO Log error here?
                contentType = "application/octet-stream";
            }
            metadata.setContentType(contentType);
            metadata.setContentEncoding("gzip");
            putObjectRequest.withMetadata(metadata);
        }
        s3.putObject(putObjectRequest);

        // Add to the file storage after. This method moves the File.
        localFileStorage.moveIntoStorage(key, file);
    }

    public File getFile(FileStorageKey key) {
        File localFile = localFileStorage.getFile(key);
        // A File object can represent a filesystem path for a file that doesn't exist yet, in which case we create it.
        if (!localFile.exists()) {
            // Before writing, ensure that the directory exists
            localFile.getParentFile().mkdirs();

            S3Object s3Object = s3.getObject(key.bucket, key.path);
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

    public String getURL (FileStorageKey key) {
        Date expiration = new Date();
        // 1 week
        int signedUrlTimeout = 3600 * 1000 * 24 * 7;
        expiration.setTime(expiration.getTime() + signedUrlTimeout);

        GeneratePresignedUrlRequest presigned = new GeneratePresignedUrlRequest(key.bucket, key.path)
                .withMethod(HttpMethod.GET)
                .withExpiration(expiration);

        return s3.generatePresignedUrl(presigned).toString();
    }

    public void delete(FileStorageKey key) {
        localFileStorage.delete(key);
        s3.deleteObject(key.bucket, key.path);
    }

    public boolean exists(FileStorageKey key) {
        if (localFileStorage.exists(key)) {
            return true;
        }
        return s3.doesObjectExist(key.bucket, key.path);
    }
}
