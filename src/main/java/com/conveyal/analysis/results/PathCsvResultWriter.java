package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

public class PathCsvResultWriter extends CsvResultWriter {

    public PathCsvResultWriter (RegionalTask task, FileStorage fileStorage) {
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
                checkDimension(workResult, "columns", iterationDetails.length, PathResult.DATA_COLUMNS.length);
            }
        }
    }

}
