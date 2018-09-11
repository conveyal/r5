package com.conveyal.r5.speed_test.test;

import com.conveyal.r5.profile.mcrr.util.Debug;
import com.conveyal.r5.profile.mcrr.util.DebugState;
import com.conveyal.r5.profile.mcrr.util.TimeUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.conveyal.r5.speed_test.test.OutputTable.Align.Center;
import static com.conveyal.r5.speed_test.test.OutputTable.Align.Left;
import static com.conveyal.r5.speed_test.test.OutputTable.Align.Right;


/**
 * This class is responsible for creating a test report as a table.
 * The Table is easy to read and can be printed to a terminal window.
 */
public class TableTestReport {

    public static String report(List<Result> results) {
        if(results.isEmpty()) {
            return "NO RESULTS FOUND FOR TEST CASE!";
        }

        Collections.sort(results);

        OutputTable table = newTable();
        for (Result it : results) {
            addTo(table, it);
        }
        return table.toString();
    }


    /* private methods */

    private static OutputTable newTable() {
        return new OutputTable(
                Arrays.asList(Center, Right, Right, Right, Right, Right, Center, Center, Left, Left, Left),
                Arrays.asList("STATUS", "TF", "Duration", "Walk", "Start", "End", "Modes", "Agencies", "Routes", "Stops", "Legs")
        );
    }

    private static void addTo(OutputTable table, Result result) {
        table.addRow(
                result.status.label,
                result.transfers,
                TimeUtils.timeToStrCompact(result.duration),
                result.walkDistance,
                // Strip of seconds for faster reading - most service schedules are by the minute not seconds
                Debug.isDebug() ? result.startTime : result.startTime.substring(0, 5),
                // Strip of seconds for faster reading - most service schedules are by the minute not seconds
                Debug.isDebug() ? result.endTime : result.endTime.substring(0, 5),
                toStr(result.modes),
                toStr(result.agencies),
                toStr(result.routes),
                intsToStr(result.stops),
                result.details
        );
    }

    private static String intsToStr(Collection<Integer> list) {
        return list.isEmpty() ? "-" : list.stream().map(Object::toString).collect(Collectors.joining(" "));
    }
    private static String toStr(Collection<String> list) {
        return list.isEmpty() ? "-" : String.join(" ", list);
    }
}
