package com.conveyal.analysis.results;

import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

/**
 * Adapts our collection of grid writers (one for each destination pointset and percentile) to give them the
 * same interface as our CSV writers, so CSV and Grids can be processed similarly in MultiOriginAssembler.
 */
public class MultiGridResultWriter implements RegionalResultWriter {
    /**
     * We create one GridResultWriter for each destination pointset and percentile.
     * Each of those output files contains data for all thresholds (time or cumulative access) at each origin.
     */
    private final GridResultWriter[][] gridResultWriters;

    private final GridResultType gridResultType;

    /** Constructor */
    public MultiGridResultWriter (
        RegionalAnalysis regionalAnalysis, RegionalTask task, int nThresholds, FileStorage fileStorage, GridResultType gridResultType
    ) {
        this.gridResultType = gridResultType;
        int nPercentiles = task.percentiles.length;
        int nDestinationPointSets = task.makeTauiSite ? 0 : task.destinationPointSetKeys.length;
        WebMercatorExtents extents = WebMercatorExtents.forTask(task);
        // Create one grid writer per percentile and destination pointset.
        gridResultWriters = new GridResultWriter[nDestinationPointSets][nPercentiles];
        for (int d = 0; d < nDestinationPointSets; d++) {
            for (int p = 0; p < nPercentiles; p++) {
                String extension = gridResultType == GridResultType.ACCESS ? "access" : "dual.access";
                String fileName = String.format(
                        "%s_%s_P%d.%s",
                        regionalAnalysis._id,
                        regionalAnalysis.destinationPointSetIds[d],
                        task.percentiles[p],
                        extension
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
        int[][][] values = gridResultType == GridResultType.ACCESS ? workResult.accessibilityValues : workResult.dualAccessValues;  
        // Drop work results for this particular origin into a little-endian output file.
        for (int d = 0; d < values.length; d++) {
            int[][] percentilesForGrid = values[d];
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
