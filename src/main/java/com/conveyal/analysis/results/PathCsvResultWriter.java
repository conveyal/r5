package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.apache.commons.lang3.ArrayUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

public class PathCsvResultWriter extends CsvResultWriter {

    public PathCsvResultWriter (RegionalTask task, FileStorage fileStorage) throws IOException {
        super(task, fileStorage);
    }

    @Override
    public CsvResultType resultType () {
        return CsvResultType.PATHS;
    }

    @Override
    public String[] columnHeaders () {
        return ArrayUtils.addAll(new String[] {"origin", "destination"}, PathResult.DATA_COLUMNS);
    }

    @Override
    public Iterable<String[]> rowValues (RegionalWorkResult workResult) {
        List<String[]> rows = new ArrayList<>();
        for (int d = 0; d < workResult.pathResult.length; d++) {
            ArrayList<String[]> pathsIterations = workResult.pathResult[d];
            for (String[] iterationDetails : pathsIterations) {
                String originId = task.originPointSet.getId(workResult.taskId);
                String destinationId = destinationId(workResult.taskId, d);
                rows.add(ArrayUtils.addAll(new String[] {originId, destinationId},  iterationDetails));
            }
        }
        return rows;
    }

    // Around 2024-04 we wanted to expand the number of CSV columns and needed to update the dimension checks below.
    // The number of columns is checked twice, once in this specific CsvResultWriter implementation and once in the
    // abstract superclass.
    // We don't want to introduce a column count check with tolerance that is applied separately to each row, because
    // this will not catch a whole class of problems where the worker instances are not producing a consistent number
    // of columns across origins.
    // We do ideally want to allow experimental workers that add an unknown number of columns, but they should add those
    // columns to every row. This requires some kind of negotiated, flexible protocol between the backend and workers.
    // Or some system where the first worker response received sets expectations and all other responses must match.
    // We thought this through and decided it was too big a change to introduce immediately.
    // So we only accept one specific quantity of CSV columns, but fail with a very specific message when we see a
    // number of CSV columns that we recognize as coming from an obsolete worker version. Breaking backward
    // compatibility is acceptable here because CSV paths are still considered an experimental feature.
    // Ideally this very case-specific check and error message will be removed when some more general system is added.

    @Override
    protected void checkDimension (RegionalWorkResult workResult) {
        // Path CSV output only supports a single freeform pointset for now.
        checkState(task.destinationPointSets.length == 1);
        // In one-to-one mode, we expect only one value per origin, the destination point at the same pointset index as
        // the origin point. Otherwise, for each origin, we expect one value per destination.
        final int nDestinations = task.oneToOne ? 1 : task.destinationPointSets[0].featureCount();
        checkDimension(workResult, "destinations", workResult.pathResult.length, nDestinations);
        for (ArrayList<String[]> oneDestination : workResult.pathResult) {
            // Number of distinct paths per destination is variable, don't validate it.
            for (String[] iterationDetails : oneDestination) {
                if (iterationDetails.length == 10) {
                    throw new IllegalArgumentException(
                            "Please use worker version newer than v7.1. CSV columns in path results have changed."
                    );
                }
                checkDimension(workResult, "columns", iterationDetails.length, PathResult.DATA_COLUMNS.length);
            }
        }
    }

}
