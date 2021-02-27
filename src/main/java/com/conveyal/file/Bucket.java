package com.conveyal.file;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Most interactions with the FileStore use a specific "bucket". To avoid passing a "bucket" everywhere the file store
 * is needed, create a wrapper class that stores that information.
 */
public class Bucket {
    private final String bucket;
    private final FileStorage fileStore;
    public Bucket(String bucket, FileStorage fileStore) {
        this.bucket = bucket;
        this.fileStore = fileStore;
    }

    public void moveIntoStorage(String key, File file) {
        fileStore.moveIntoStorage(new FileStorageKey(bucket, key), file);
    }

    public File getFile(String key) {
        return fileStore.getFile(new FileStorageKey(bucket, key));
    }

    public String getURL(String key) {
        return fileStore.getURL(new FileStorageKey(bucket, key));
    }

    public void delete(String key) {
        fileStore.delete(new FileStorageKey(bucket, key));
    }

    public boolean exists(String key) {
        return fileStore.exists(new FileStorageKey(bucket, key));
    }

    public BiConsumer<String, File> createMoveIntoStorage () {
        return fileStore.createMoveIntoStorage(bucket);
    }

    /**
     * Create a `getFile` method with a pre-defined bucket.
     */
    public Function<String, File> createGetFile () {
        return fileStore.createGetFile(bucket);
    }
}
