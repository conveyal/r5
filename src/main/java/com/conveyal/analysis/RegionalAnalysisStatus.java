package com.conveyal.analysis;


import com.conveyal.analysis.results.MultiOriginAssembler;

import java.io.Serializable;

/**
 * This model object is sent to the UI serialized as JSON in order to report regional job progress.
 */
public final class RegionalAnalysisStatus implements Serializable {
    public int total;
    public int complete;

    public RegionalAnalysisStatus() { /* No-arg constructor for deserialization only. */ }

    public RegionalAnalysisStatus(MultiOriginAssembler assembler) {
        total = assembler.nOriginsTotal;
        complete = assembler.nComplete;
    }
}
