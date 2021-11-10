package com.conveyal.file;

import com.conveyal.r5.analyst.PersistenceBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

/**
 * This implementation of FileStorage stores files in a local directory hierarchy and does not mirror anything to
 * cloud storage.
 */
public class LocalFileStorage implements FileStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileStorage.class);

    public interface Config {
        // The local directory where files will be stored, even if they are being mirrored to a remote storage service.
        String localCacheDirectory ();
        // The port where the browser can fetch files. Parameter name aligned with the HttpApi server port parameter.
        int serverPort ();
    }

    public final String directory;
    private final String urlPrefix;

    public LocalFileStorage (Config config) {
        this.directory = config.localCacheDirectory();
        this.urlPrefix = String.format("http://localhost:%s/files", config.serverPort());
        new File(directory).mkdirs();
    }

    /**
     * Move the File into the FileStorage by moving the passed in file to the Path represented by the FileStorageKey.
     */
    @Override
    public void moveIntoStorage(FileStorageKey key, File sourceFile) {
        // Get a pointer to the local file
        File storedFile = getFile(key);
        // Ensure the directories exist
        storedFile.getParentFile().mkdirs();
        try {
            try {
                // Move the temporary file to the permanent file location.
                Files.move(sourceFile.toPath(), storedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                // The default Windows filesystem (NTFS) does not unlock memory-mapped files, so certain files (e.g.
                // mapdb Write Ahead Log) cannot be moved or deleted. This workaround may cause temporary files
                // to accumulate, but it should not be triggered for default Linux filesystems (ext).
                // See https://github.com/jankotek/MapDB/issues/326
                Files.copy(sourceFile.toPath(), storedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Could not move {} because of FileSystem restrictions (probably NTFS). Copied instead.",
                        sourceFile.getName());
            }
            setReadOnly(storedFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void moveIntoStorage (FileStorageKey fileStorageKey, PersistenceBuffer persistenceBuffer) {
        throw new UnsupportedOperationException("In-memory buffers are only persisted to cloud storage.");
    }

    @Override
    public File getFile(FileStorageKey key) {
        return new File(String.join("/", directory, key.category.directoryName(), key.path));
    }

    /**
     * Return a URL for the file as accessed through the backend's own static file server.
     * (Registered in HttpApi at .get("/files/:category/*"))
     * This exists to allow the same UI to work locally and in cloud deployments.
     */
    @Override
    public String getURL (FileStorageKey key) {
        return String.join("/", urlPrefix, key.category.directoryName(), key.path);
    }

    @Override
    public void delete (FileStorageKey key) {
        try {
            File storedFile = getFile(key);
            if (storedFile.exists()) {
                // File permissions are set read-only to prevent corruption, so must be changed to allow deletion.
                Files.setPosixFilePermissions(storedFile.toPath(), Set.of(OWNER_READ, OWNER_WRITE));
                storedFile.delete();
            } else {
                LOG.warn("Attempted to delete non-existing file: " + storedFile);
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception while deleting stored file.", e);
        }
    }

    @Override
    public boolean exists(FileStorageKey key) {
        return getFile(key).exists();
    }

    /**
     * Set the file to be read-only and accessible only by the current user.
     * There are several ways to set read-only, but this one works on both POSIX and Windows systems.
     */
    public static void setReadOnly (File file) {
        if (!file.setWritable(false, false)) {
            LOG.error("Could not restrict permissions on {} to read-only by owner: ", file.getName());
        }
    }

}
