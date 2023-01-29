package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

public class TimeCsvResultWriter extends CsvResultWriter {

    public TimeCsvResultWriter (RegionalTask task, FileStorage fileStorage) {
        super(task, fileStorage);
    }

    @Override
    public CsvResultType resultType () {
        return CsvResultType.TIMES;
    }

    @Override
    public String[] columnHeaders () {
        return new String[] { "origin", "destination", "percentile", "time" };
    }

    /**
     * Check that each dimension of the 2D results array matches the expected size for the job being processed.
     */
    @Override
    protected void checkDimension (RegionalWorkResult workResult) {
        // TODO handle multiple destination pointsets at once?
        checkState(
           task.destinationPointSets != null &&
           task.destinationPointSets.length == 1 &&
           task.destinationPointSets[0] instanceof FreeFormPointSet,
           "Time CSV writer expects only a single freeform destination pointset."
        );
        // In one-to-one mode, we expect only one value per origin, the destination point at the same pointset index as
        // the origin point. Otherwise, for each origin, we expect one value per destination.
        final int nDestinations = task.oneToOne ? 1 : task.destinationPointSets[0].featureCount();
        checkDimension(workResult, "percentiles", workResult.travelTimeValues.length, task.percentiles.length);
        for (int[] percentileResult : workResult.travelTimeValues) {
            checkDimension(workResult, "destinations", percentileResult.length, nDestinations);
        }
    }

    @Override
    public Iterable<String[]> rowValues (RegionalWorkResult workResult) {
        // Can nested iteration be made identical for all CSV writer subclasses, factoring it out to the superclass?
        String originId = task.originPointSet.getId(workResult.taskId);
        List<String[]> rows = new ArrayList<>();
        // Only one destination pointset for now.
        for (int p = 0; p < task.percentiles.length; p++) {
            int[] percentileResult = workResult.travelTimeValues[p];
            for (int d = 0; d < percentileResult.length; d++) {
                int travelTime = percentileResult[d];
                if (travelTime == Integer.MAX_VALUE) {
                    travelTime = -1; // Replace 2^31 with less arcane value for end user export.
                }
                String destinationId = destinationId(workResult.taskId, d);
                rows.add(new String[] {
                    originId, destinationId, Integer.toString(task.percentiles[p]), Integer.toString(travelTime)
                });
            }
        }
        return rows;
    }

}
