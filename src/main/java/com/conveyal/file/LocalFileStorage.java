package com.conveyal.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class LocalFileStorage implements FileStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileStorage.class);

    public final String directory;
    private final String urlPrefix;

    public LocalFileStorage (String localCacheDirectory) {
        this(localCacheDirectory, "http://localhost:7070");
    }

    public LocalFileStorage (String localCacheDirectory, String urlPrefix) {
        this.directory = localCacheDirectory;
        this.urlPrefix = urlPrefix;

        File directory = new File(localCacheDirectory);
        directory.mkdirs();
    }

    /**
     * Move the File into the FileStorage by moving the passed in file to the Path represented by the FileStorageKey.
     */
    public void moveIntoStorage(FileStorageKey key, File file) {
        // Get a pointer to the local file
        File storedFile = getFile(key);
        // Ensure the directories exist
        storedFile.getParentFile().mkdirs();
        try {
            try {
                // Move the temporary file to the permanent file location.
                Files.move(file.toPath(), storedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                // The default Windows filesystem (NTFS) does not unlock memory-mapped files, so certain files (e.g.
                // mapdb) cannot be moved or deleted. This workaround may cause temporary files to accumulate, but it
                // should not be triggered for default Linux filesystems (ext).
                // See https://github.com/jankotek/MapDB/issues/326
                Files.copy(file.toPath(), storedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Could not move {} because of FileSystem restrictions (probably NTFS). Copying instead.",
                        file.getName());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public File getFile(FileStorageKey key) {
        return new File(String.join("/", directory, key.bucket, key.path));
    }

    /**
     * This will only get called in offline mode.
     */
    public String getURL (FileStorageKey key) {
        return String.join("/", urlPrefix, key.bucket, key.path);
    }

    public void delete (FileStorageKey key) {
        getFile(key).delete();
    }

    public boolean exists(FileStorageKey key) {
        return getFile(key).exists();
    }

}
