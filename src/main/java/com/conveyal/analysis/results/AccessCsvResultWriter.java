package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AccessCsvResultWriter extends CsvResultWriter {

    public AccessCsvResultWriter (RegionalTask task, FileStorage fileStorage) throws IOException {
        super(task, fileStorage);
    }

    @Override
    public CsvResultType resultType () {
        return CsvResultType.ACCESS;
    }

    @Override
    public String[] columnHeaders () {
        // We could potentially make the percentile and cutoff columns optional, but it's unnecessary complexity.
        return new String[] { "origin", "destinations", "percentile", "cutoff", "access" };
    }

    /**
     * Check that each dimension of the 3D accessibility results array
     * matches the expected size for the job being processed.
     */
    @Override
    protected void checkDimension (RegionalWorkResult workResult) {
        checkDimension(workResult, "destination pointsets", workResult.accessibilityValues.length, task.destinationPointSetKeys.size());
        for (int[][] percentilesForGrid : workResult.accessibilityValues) {
            checkDimension(workResult, "percentiles", percentilesForGrid.length, task.percentiles.size());
            for (int[] cutoffsForPercentile : percentilesForGrid) {
                checkDimension(workResult, "cutoffs", cutoffsForPercentile.length, task.cutoffsMinutes.size());
            }
        }
    }

    @Override
    public Iterable<String[]> rowValues (RegionalWorkResult workResult) {
        String originId = task.originPointSet.getId(workResult.taskId);
        List<String[]> rows = new ArrayList<>();
        for (int d = 0; d < task.destinationPointSetKeys.size(); d++) {
            int[][] percentilesForDestPointset = workResult.accessibilityValues[d];
            for (int p = 0; p < task.percentiles.size(); p++) {
                int[] cutoffsForPercentile = percentilesForDestPointset[p];
                for (int c = 0; c < task.cutoffsMinutes.size(); c++) {
                    int accessibilityValue = cutoffsForPercentile[c];
                    // Ideally we'd output the pointset IDs (rather than keys) which we have in the RegionalAnalysis
                    rows.add(new String[]{
                            originId,
                            task.destinationPointSetKeys.get(d),
                            Integer.toString(task.percentiles.get(p)),
                            Integer.toString(task.cutoffsMinutes.get(c)),
                            Integer.toString(accessibilityValue)
                    });
                }
            }
        }
        return rows;
    }

}
