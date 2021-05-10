package com.conveyal.file;

/**
 * A unique identifier for a file within a namespace drawn from an enum of known categories.
 * This maps to a subdirectory and filename in local storage, and a bucket and object key in S3-style cloud storage.
 * While keeping stored files in multiple distinct categories, this avoids passing around a lot of directory/bucket
 * names as strings, and avoids mistakes where such strings are mismatched accross different function calls.
 */
public class FileStorageKey {

    public final FileCategory category;
    public final String path; // rename field to id or name? these are not usually (never?) paths, just object names.

    public FileStorageKey(FileCategory category, String path) {
        checkForDirectoryTraversal(path);
        this.category = category;
        this.path = path;
    }

    public FileStorageKey(FileCategory category, String path, String ext) {
        this(category, path + "." + ext);
    }

    @Override
    public String toString () {
        return String.format("[File storage key: category='%s', key='%s']", category, path);
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
