package com.conveyal.analysis.results;

import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.IOException;

/**
 * Common interface for classes that write regional results out to CSV or Grids on the backend.
 */
interface RegionalResultWriter {

    void writeOneWorkResult (RegionalWorkResult workResult) throws Exception;

    void terminate () throws Exception;

    void finish () throws Exception;

}
