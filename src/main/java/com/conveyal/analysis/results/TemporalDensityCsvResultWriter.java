package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This handles collating regional results into CSV files containing temporal opportunity density
 * (number of opportunities reached in each one-minute interval, the derivative of step-function accessibility)
 * as well as "dual" accessibility (the amount of time needed to reach n opportunities).
 */
public class TemporalDensityCsvResultWriter extends CsvResultWriter {

    private final int dualThreshold;

    public TemporalDensityCsvResultWriter(RegionalTask task, FileStorage fileStorage) throws IOException {
        super(task, fileStorage);
        dualThreshold = task.dualAccessibilityThreshold;
    }

    @Override
    public CsvResultType resultType () {
        return CsvResultType.TDENSITY;
    }

    @Override
    public String[] columnHeaders () {
        List<String> headers = new ArrayList<>();
        // The ids of the freeform origin point and destination set
        headers.add("origin");
        headers.add("destinations");
        headers.add("percentile");
        // The number of minutes needed to reach d destination opportunities
        headers.add("dual");
        // The opportunity density during each of 120 minutes
        for (int m = 0; m < 120; m += 1) {
            headers.add(Integer.toString(m));
        }
        return headers.toArray(new String[0]);
    }

    @Override
    protected void checkDimension (RegionalWorkResult workResult) {
        checkDimension(
            workResult, "destination pointsets",
            workResult.opportunitiesPerMinute.length, task.destinationPointSetKeys.length
        );
        for (double[][] percentilesForPointset : workResult.opportunitiesPerMinute) {
            checkDimension(workResult, "percentiles", percentilesForPointset.length, task.percentiles.length);
            for (double[] minutesForPercentile : percentilesForPointset) {
                checkDimension(workResult, "minutes", minutesForPercentile.length, 120);
            }
        }
    }

    @Override
    public Iterable<String[]> rowValues (RegionalWorkResult workResult) {
        List<String[]> rows = new ArrayList<>();
        String originId = task.originPointSet.getId(workResult.taskId);
        for (int d = 0; d < task.destinationPointSetKeys.length; d++) {
            double[][] percentilesForDestPointset = workResult.opportunitiesPerMinute[d];
            for (int p = 0; p < task.percentiles.length; p++) {
                List<String> row = new ArrayList<>(125);
                row.add(originId);
                row.add(task.destinationPointSetKeys[d]);
                row.add(Integer.toString(task.percentiles[p]));
                // One column containing dual accessibility value
                double[] densitiesPerMinute = percentilesForDestPointset[p];
                int m = 0;
                double sum = 0;
                // Find smallest integer M such that we have already reached D destinations after M minutes of travel.
                while (sum < dualThreshold && m < 120) {
                    sum += densitiesPerMinute[m];
                    m += 1;
                }
                // -1 indicates the threshold number of opportunities had still not been reached after the highest
                // travel time cutoff specified in the analysis.
                row.add(Integer.toString(m >= 120 ? -1 : m));
                // One density value for each of 120 one-minute bins.
                // Column labeled 10 contains the number of opportunities reached after 10 to 11 minutes of travel.
                for (m = 0; m < 120; m++) {
                    row.add(Double.toString(densitiesPerMinute[m]));
                }
                rows.add(row.toArray(new String[row.size()]));
            }
        }
        return rows;
    }

}
