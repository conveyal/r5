package com.conveyal.analysis.results;

import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.File;
import java.io.IOException;

/**
 * This is an abstract base class for writing regional analysis results into a file for long term
 * storage. It provides reuseable logic for creating local buffer files and uploading them to long
 * term cloud storage once the regional analysis is complete. Concrete subclasses handle writing CSV
 * or proprietary binary grid files, depending on the type of regional analysis.
 */
public abstract class BaseResultWriter {
    protected File bufferFile = FileUtils.createScratchFile();

    abstract public void writeOneWorkResult (RegionalWorkResult workResult) throws IOException;

    public File finish() throws IOException {
        return bufferFile;
    }
    
    /**
     * Close all buffers and temporary files.
     */
    protected void terminate () throws IOException {
        bufferFile.delete();
    }
}
