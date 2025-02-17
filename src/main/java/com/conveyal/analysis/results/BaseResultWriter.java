package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.file.FileCategory.RESULTS;
import static com.conveyal.r5.common.Util.human;

/**
 * This is an abstract base class for writing regional analysis results into a file for long term
 * storage. It provides reuseable logic for creating local buffer files and uploading them to long
 * term cloud storage once the regional analysis is complete. Concrete subclasses handle writing CSV
 * or proprietary binary grid files, depending on the type of regional analysis.
 */
public abstract class BaseResultWriter {

    private static final Logger LOG = LoggerFactory.getLogger(BaseResultWriter.class);

    public final String fileName;
    private final FileStorage fileStorage;

    protected File bufferFile = FileUtils.createScratchFile();

    public BaseResultWriter(String fileName, FileStorage fileStorage) {
        this.fileName = fileName;
        this.fileStorage = fileStorage;
    }

    /**
     * Gzip the access grid and store it.
     */
    protected synchronized void finish() throws IOException {
        LOG.info("Compressing {} and moving into file storage.", fileName);
        FileStorageKey fileStorageKey = new FileStorageKey(RESULTS, fileName);
        File gzippedResultFile = FileUtils.createScratchFile();

        // There's probably a more elegant way to do this with NIO and without closing the buffer.
        // That would be Files.copy(File.toPath(),X) or ByteStreams.copy.
        // Perhaps better: we could wrap the output buffer in a gzip output stream and zip as we write out.
        InputStream is = new BufferedInputStream(new FileInputStream(bufferFile));
        OutputStream os = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(gzippedResultFile)));
        ByteStreams.copy(is, os);
        is.close();
        os.close();

        LOG.info("GZIP compression reduced analysis results {} from {} to {} ({}x compression)",
                fileName,
                human(bufferFile.length(), "B"),
                human(gzippedResultFile.length(), "B"),
                (double) bufferFile.length() / gzippedResultFile.length()
        );

        fileStorage.moveIntoStorage(fileStorageKey, gzippedResultFile);
        bufferFile.delete();
    }

    /**
     * Close all buffers and temporary files.
     */
    abstract void terminate () throws Exception;

}
