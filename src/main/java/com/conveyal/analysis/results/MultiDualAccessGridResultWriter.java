package com.conveyal.analysis.results;

import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

public class MultiDualAccessGridResultWriter implements RegionalResultWriter {
    /**
     * We create one GridResultWriter for each destination pointset and percentile.
     * Each of those output files contains data for all thresholds (time or cumulative access) at each origin.
     */
    private final GridResultWriter[][] gridResultWriters;

    /** Constructor */
    public MultiDualAccessGridResultWriter (
            RegionalAnalysis regionalAnalysis, RegionalTask task, int nThresholds, FileStorage fileStorage
    ) {
        int nPercentiles = task.percentiles.length;
        int nDestinationPointSets = task.makeTauiSite ? 0 : task.destinationPointSetKeys.length;
        WebMercatorExtents extents = WebMercatorExtents.forTask(task);
        // Create one grid writer per percentile and destination pointset.
        gridResultWriters = new GridResultWriter[nDestinationPointSets][nPercentiles];
        for (int d = 0; d < nDestinationPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                String fileName = String.format(
                        "%s_%s_P%d.dual.access",
                        regionalAnalysis._id,
                        regionalAnalysis.destinationPointSetIds[d],
                        task.percentiles[p]
                );
                gridResultWriters[d][p] = new GridResultWriter(
                        extents,
                        nThresholds,
                        fileName,
                        fileStorage
                );
            }
        }
    }

    @Override
    public void writeOneWorkResult (RegionalWorkResult workResult) throws Exception {
        for (int d = 0; d < workResult.dualAccessValues.length; d++) {
            int[][] percentilesForGrid = workResult.dualAccessValues[d];
            for (int p = 0; p < percentilesForGrid.length; p++) {
                GridResultWriter gridWriter = gridResultWriters[d][p];
                gridWriter.writeOneOrigin(workResult.taskId, percentilesForGrid[p]);
            }
        }
    }

    @Override
    public void terminate () throws Exception {
        for (GridResultWriter[] writers : gridResultWriters) {
            for (GridResultWriter writer : writers) {
                writer.terminate();
            }
        }
    }

    @Override
    public void finish () throws Exception {
        for (GridResultWriter[] gridResultWriterRow : gridResultWriters) {
            for (GridResultWriter resultWriter : gridResultWriterRow) {
                resultWriter.finish();
            }
        }
    }

}
