package com.conveyal.file;

/**
 * Represent S3 bucket/keys and locally cached folder/paths. Prevents multiple parameter passing or many
 * separate `String.join("/", ...)`s.
 */
public class FileStorageKey {
    public final String bucket; // Rather than a bucket, this could be just a folder in a cache directory.
    public final String path;

    public FileStorageKey(String fullPath) {
        checkForDirectoryTraversal(fullPath);
        int slashIndex = fullPath.indexOf("/");
        bucket = fullPath.substring(0, slashIndex);
        path = fullPath.substring(slashIndex + 1);
    }

    public FileStorageKey(String bucket, String path) {
        checkForDirectoryTraversal(path);
        this.bucket = bucket;
        this.path = path;
    }

    public FileStorageKey(String bucket, String path, String ext) {
        this(bucket, path + "." + ext);
    }

    public String getFullPath() {
        return String.join("/", bucket, path);
    }

    @Override
    public String toString () {
        return String.format("[File storage key: bucket='%s', key='%s']", bucket, path);
    }

    /**
     * Validate user-provided paths to ensure they do not contain any sequence that would allow accessing files
     * outside the intended file storage directory.
     */
    public static void checkForDirectoryTraversal (String path) {
        if (path.contains("../") || path.contains("..\\")) {
            throw new IllegalArgumentException("Path looks like it could be a directory traversal attack.");
        }
    }

}
