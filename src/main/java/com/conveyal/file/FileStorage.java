package com.conveyal.file;

import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Store files, optionally mirroring them to cloud storage for retrieval by workers and future backend deployments.
 * These are always used as files on the local filesystem, and are treated as immutable once put into storage.
 * For simplicity, methods that store and remove files are all blocking calls. If you add a file, all other components
 * of the system are known to be able to see it as soon as the method returns. This does not handle storing file
 * metadata in MongoDB. That is a separate concern. Workers need to get files without looking into our database.
 * Our file metadata handling component could wrap FileStorage, so all backend file operations implicitly had metadata.
 */
public interface FileStorage {

    /**
     * Takes an already existing file on the local filesystem and registers it as a permanent, immutable file to be
     * made available to all analysis components including workers and future backends. If a file was uploaded in a
     * form, we can call DiskFileItem.getStoreLocation to get the file, which according to that method's Javadoc we are
     * allowed to rename to our own location. If the file was created by the backend, it should be created in a temp
     * file. Once the file is completely constructed/written out, it should be closed and then this method called on it.
     */
    void moveIntoStorage(FileStorageKey fileStorageKey, File file);

    /**
     * Move the data in an in-memory buffer into permanent storage, much like moveIntoStorage(key, file).
     * The PersistenceBuffer must be marked 'done' before it is handed to this method.
     * Files in the TAUI category are treated in a special way: they are not kept locally if mirrored remotely.
     * Unlike the other file categories, these are produced on the worker (as opposed to the backend), and will never
     * be read by the worker, so don't need to be kept once stored to S3.
     *
     * This is a blocking call and should only return when the file is completely uploaded. This prevents workers from
     * producing output faster than uploads can complete, avoiding a growing queue of waiting uploads.
     *
     * TODO call with FileStorageKey(TAUI, analysisWorkerTask.jobId);
     * TODO eventually unify with moveIntoStorage, by wrapping File in FileStorageBuffer?
     */
    void moveIntoStorage(FileStorageKey fileStorageKey, PersistenceBuffer persistenceBuffer);

    /**
     * Files returned from this method must be treated as immutable. Never write to them. Immutability could be
     * enforced by making our own class with no write methods and only allows reading the file. It may be more
     * practical to set the file's access flags to read-only so exceptions will occur if we ever write.
     */
    File getFile(FileStorageKey fileStorageKey);

    /**
     * Get the URL for the File identified by the FileStorageKey. This provides a way for a browser-based UI to read
     * the file without going through the backend. This can be an S3 URL available over the web or a file:// URL when
     * running locally.
     */
    String getURL(FileStorageKey fileStorageKey);

    /**
     * Delete the File identified by the FileStorageKey, in both the local cache and any remote mirror.
     */
    void delete(FileStorageKey fileStorageKey);

    /**
     * TODO explain what this method actually does.
     * When a new server is spun up there will be no local files. In instances where we derive files from other files
     * (ex: creating Tiffs from Grids) and if they are already created we only need to return a download URL and therefore
     * not need to retrieve the file at all, it would be useful to check if the file exists in the FileStorage without
     * actually retrieving it.
     */
    boolean exists(FileStorageKey fileStorageKey);


    //// Convenience methods usable with all concrete subclasses.

    /** Store Taui output in subfolders by job ID. */
    default void saveTauiData (AnalysisWorkerTask task, String fileName, PersistenceBuffer buffer) {
        FileStorageKey key = new FileStorageKey(FileCategory.TAUI, String.join("/", task.jobId, fileName));
        moveIntoStorage(key, buffer);
    }

    /** Read from a file as a stream, decompressing if the name indicates it's gzipped. */
    default InputStream getInputStream (FileCategory fileCategory, String fileName) throws IOException {
        InputStream inputStream = new FileInputStream(getFile(new FileStorageKey(fileCategory, fileName)));
        if (fileName.endsWith(".gz")) {
            return new GZIPInputStream(inputStream);
        } else {
            return new BufferedInputStream(inputStream);
        }
    }

}
