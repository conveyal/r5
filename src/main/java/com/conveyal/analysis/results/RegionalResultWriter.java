package com.conveyal.analysis.results;

import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.File;
import java.util.Map;

/**
 * Common interface for classes that write regional results out to CSV or Grids on the backend.
 */
public interface RegionalResultWriter {

    void writeOneWorkResult (RegionalWorkResult workResult) throws Exception;

    void terminate () throws Exception;

    Map.Entry<FileStorageKey, File> finish () throws Exception;

}
