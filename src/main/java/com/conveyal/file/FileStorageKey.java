package com.conveyal.file;

/**
 * Represent S3 bucket/keys and locally cached folder/paths. Prevents multiple parameter passing or many
 * separate `String.join("/", ...)`s.
 */
public class FileStorageKey {

    public final FileCategory category;
    public final String path;

    public FileStorageKey(FileCategory category, String path) {
        checkForDirectoryTraversal(path);
        this.category = category;
        this.path = path;
    }

    public FileStorageKey(FileCategory category, String path, String ext) {
        this(category, path + "." + ext);
    }

    public String getFullPath() {
        return String.join("/", category.directoryName(), path);
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
