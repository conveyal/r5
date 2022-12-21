package com.conveyal.analysis.results;

import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

/**
 * Adapts our collection of grid writers (one for each destination pointset and percentile) to give them the
 * same interface as our CSV writers, so CSV and Grids can be processed similarly in MultiOriginAssembler.
 */
public class MultiGridResultWriter implements RegionalResultWriter {

    private final RegionalAnalysis regionalAnalysis;

    private final RegionalTask task;

    /**
     * We create one GridResultWriter for each destination pointset and percentile.
     * Each of those output files contains data for all travel time cutoffs at each origin.
     */
    private final GridResultWriter[][] accessibilityGridWriters;

    /** The number of different percentiles for which we're calculating accessibility on the workers. */
    private final int nPercentiles;

    /** The number of destination pointsets to which we're calculating accessibility */
    private final int nDestinationPointSets;

    /** Constructor */
    public MultiGridResultWriter (
            RegionalAnalysis regionalAnalysis, RegionalTask task, FileStorage fileStorage
    ) {
        // We are storing the regional analysis just to get its pointset IDs (not keys) and its own ID.
        this.regionalAnalysis = regionalAnalysis;
        this.task = task;
        this.nPercentiles = task.percentiles.length;
        this.nDestinationPointSets = task.makeTauiSite ? 0 : task.destinationPointSetKeys.length;
        // Create one grid writer per percentile and destination pointset.
        accessibilityGridWriters = new GridResultWriter[nDestinationPointSets][nPercentiles];
        for (int d = 0; d < nDestinationPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                accessibilityGridWriters[d][p] = new GridResultWriter(task, fileStorage);
            }
        }
    }

    @Override
    public void writeOneWorkResult (RegionalWorkResult workResult) throws Exception {
        // Drop work results for this particular origin into a little-endian output file.
        // TODO more efficient way to write little-endian integers
        // TODO check monotonic increasing invariants here rather than in worker.
        // Infer x and y cell indexes based on the template task
        int taskNumber = workResult.taskId;
        for (int d = 0; d < workResult.accessibilityValues.length; d++) {
            int[][] percentilesForGrid = workResult.accessibilityValues[d];
            for (int p = 0; p < nPercentiles; p++) {
                int[] cutoffsForPercentile = percentilesForGrid[p];
                GridResultWriter gridWriter = accessibilityGridWriters[d][p];
                gridWriter.writeOneOrigin(taskNumber, cutoffsForPercentile);
            }
        }
    }

    @Override
    public void terminate () throws Exception {
        for (GridResultWriter[] writers : accessibilityGridWriters) {
            for (GridResultWriter writer : writers) {
                writer.terminate();
            }
        }
    }

    @Override
    public void finish () throws Exception {
        for (int d = 0; d < nDestinationPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                int percentile = task.percentiles[p];
                String destinationPointSetId = regionalAnalysis.destinationPointSetIds[d];
                // TODO verify that regionalAnalysis._id is the same as job.jobId
                String gridFileName =
                        String.format("%s_%s_P%d.access", regionalAnalysis._id, destinationPointSetId, percentile);
                accessibilityGridWriters[d][p].finish(gridFileName);
            }
        }
    }

}
