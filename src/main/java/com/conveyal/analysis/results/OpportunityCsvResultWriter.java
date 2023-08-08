package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This handles collating regional results into CSV files containing temporal opportunity density
 * (number of opportunities reached in each one-minute interval, the derivative of step-function accessibility)
 * as well as "dual" accessibility (the amount of time needed to reach n opportunities).
 * And maybe the N closest opportunities to each origin?
 */
public class OpportunityCsvResultWriter extends CsvResultWriter {

    private final int dualOpportunityCount;

    public OpportunityCsvResultWriter(RegionalTask task, FileStorage fileStorage) throws IOException {
        super(task, fileStorage);
        dualOpportunityCount = task.dualAccessibilityOpportunityThreshold;
    }

    @Override
    public CsvResultType resultType () {
        return CsvResultType.OPPORTUNITIES;
    }

    @Override
    public String[] columnHeaders () {
        List<String> headers = new ArrayList<>();
        // The ids of the freeform origin point and destination set
        headers.add("originId");
        headers.add("destId");
        headers.add("percentile");
        for (int m = 0; m < 120; m += 1) {
            // The opportunity density over travel minute m
            headers.add(Integer.toString(m));
        }
        // The number of minutes needed to reach d destination opportunities
        headers.add("D" + dualOpportunityCount);
        return headers.toArray(new String[0]);
    }

    @Override
    protected void checkDimension (RegionalWorkResult workResult) {
        checkDimension(workResult, "destination pointsets", workResult.opportunitiesPerMinute.length, task.destinationPointSetKeys.length);
        for (double[][] percentilesForPointset : workResult.opportunitiesPerMinute) {
            checkDimension(workResult, "percentiles", percentilesForPointset.length, task.percentiles.length);
            for (double[] minutesForPercentile : percentilesForPointset) {
                checkDimension(workResult, "minutes", minutesForPercentile.length, 120);
            }
        }
    }

    @Override
    public Iterable<String[]> rowValues (RegionalWorkResult workResult) {
        List<String> row = new ArrayList<>(125);
        String originId = task.originPointSet.getId(workResult.taskId);
        for (int d = 0; d < task.destinationPointSetKeys.length; d++) {
            int[][] percentilesForDestPointset = workResult.accessibilityValues[d];
            for (int p = 0; p < task.percentiles.length; p++) {
                row.add(originId);
                row.add(task.destinationPointSets[d].name);
                row.add(Integer.toString(p));
                int[] densitiesPerMinute = percentilesForDestPointset[p];
                for (int m = 0; m < 120; m++) {
                    row.add(Double.toString(densitiesPerMinute[m]));
                }
                // Dual accessibility
                {
                    int m = 0;
                    double sum = 0;
                    while (sum < dualOpportunityCount) {
                        sum += densitiesPerMinute[m];
                        m += 1;
                    }
                    row.add(Integer.toString(m >= 120 ? -1 : m));
                }
            }
        }
        // List.of() or Arrays.asList() don't work without explicitly specifying the generic type because
        // they interpret the String[] as varargs in the method signature.
        return List.<String[]>of(row.toArray(new String[0]));
    }

}
