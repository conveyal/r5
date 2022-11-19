package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.r5.common.Util.human;

/**
 * This is an abstract base class for writing regional analysis results into a file for long term
 * storage. It provides reuseable logic for creating local buffer files and uploading them to long
 * term cloud storage once the regional analysis is complete. Concrete subclasses handle writing CSV
 * or proprietary binary grid files, depending on the type of regional analysis.
 */
public abstract class BaseResultWriter {

    private static final Logger LOG = LoggerFactory.getLogger(BaseResultWriter.class);

    protected final File bufferFile = FileUtils.createScratchFile();

    protected final FileStorage fileStorage;

    BaseResultWriter(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Gzip the access grid and store it.
     */
    protected synchronized File gzipBufferedResults() throws IOException {
        var gzippedResultFile = FileUtils.createScratchFile();
        var gzipOutputStream = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(gzippedResultFile)));
        FileUtils.transferFromFileTo(bufferFile, gzipOutputStream);

        LOG.info("GZIP compression reduced analysis results from {} to {} ({}x compression)",
                human(bufferFile.length(), "B"),
                human(gzippedResultFile.length(), "B"),
                (double) bufferFile.length() / gzippedResultFile.length()
        );

        bufferFile.delete();
        return gzippedResultFile;
    }

    /**
     * Close all buffers and temporary files.
     */
    abstract void terminate () throws Exception;

}
