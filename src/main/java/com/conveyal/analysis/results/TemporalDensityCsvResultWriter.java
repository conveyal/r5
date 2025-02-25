package com.conveyal.analysis.results;

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
    public TemporalDensityCsvResultWriter(RegionalTask task) throws IOException {
        super(task);
    }

    @Override
    public String[] columnHeaders () {
        List<String> headers = new ArrayList<>();
        // The ids of the freeform origin point and destination set
        headers.add("origin");
        headers.add("destinations");
        headers.add("percentile");
        if (task.dualAccessThresholds != null) {
            // The number of minutes needed to reach d destination opportunities
            for (int t = 0; t < task.dualAccessThresholds.length; t++) {
                headers.add(String.format("dual %d", task.dualAccessThresholds[t]));
            }
        }
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
            for (int p = 0; p < task.percentiles.length; p++) {
                List<String> row = new ArrayList<>(125);
                row.add(originId);
                row.add(task.destinationPointSetKeys[d]);
                row.add(Integer.toString(task.percentiles[p]));

                if (workResult.dualAccessValues != null) {
                    // One column for each of the dual access threshold values for this origin.
                    for (int t = 0; t < workResult.dualAccessValues[d][p].length; t++) {
                        int dualAccess = workResult.dualAccessValues[d][p][t];
                        row.add(Integer.toString(dualAccess));
                    }
                }
                // One density value for each one-minute bin.
                // Column labeled 10 contains the number of opportunities reached between 9 and 10 minutes of travel.
                double[] opportunitiesPerMinute = workResult.opportunitiesPerMinute[d][p];
                for (int m = 0; m < opportunitiesPerMinute.length; m++) {
                    row.add(Double.toString(opportunitiesPerMinute[m]));
                }
                rows.add(row.toArray(new String[row.size()]));
            }
        }
        return rows;
    }
}