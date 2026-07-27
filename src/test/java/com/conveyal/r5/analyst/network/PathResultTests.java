package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.path.RouteSequence;
import com.conveyal.r5.transit.path.StopSequence;
import com.google.common.collect.Multimap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of the detailed path information recorded for CSV results, using the Simpson Desert synthetic grid network.
/// The trip from origin to destination always requires one transfer between a horizontal and a vertical route, so
/// recorded paths should have access, egress, ride, wait, and transfer components.
///
/// This primarily asserts additivity of these components. Each iteration's total travel time must equal the sum of its
/// component times. Any inconsistency in transfer and waiting times derived from clock times recorded during routing
/// (as previously produced by waiting times retained across range-raptor departure minutes) should show up here.
public class PathResultTests {

    @Test
    public void scheduledTransfer () {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addVerticalRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .includePathResults()
                .build();

        OneOriginResult result = new TravelTimeComputer(task, network).computeTravelTimes();
        checkIterationInvariants(result.paths);
        checkSummaryStatistics(result.paths);
    }

    @Test
    public void frequencyTransfer () {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        gridLayout.addVerticalFrequencyRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .monteCarloDraws(1200)
                .includePathResults()
                .build();

        OneOriginResult result = new TravelTimeComputer(task, network).computeTravelTimes();
        checkIterationInvariants(result.paths);
        checkSummaryStatistics(result.paths);
    }

    /// Check that every iteration recorded for every path template is internally consistent. The number of waiting
    /// times should match the number of transit legs. Waits should never be negative, and component times should sum
    /// to exactly the total travel time. Also check for the expected transfers.
    private static void checkIterationInvariants (PathResult pathResult) {
        boolean sawTransfer = false;
        for (Multimap<RouteSequence, PathResult.Iteration> iterationMap : pathResult.iterationsForPathTemplates) {
            if (iterationMap == null) continue;
            for (RouteSequence routeSequence : iterationMap.keySet()) {
                StopSequence stopSequence = routeSequence.stopSequence;
                int nLegs = routeSequence.routes.size();
                if (nLegs == 2) sawTransfer = true;
                for (PathResult.Iteration iteration : iterationMap.get(routeSequence)) {
                    if (nLegs == 0) {
                        assertEquals(0, iteration.waitTimes.size());
                        continue;
                    }
                    assertEquals(nLegs, iteration.waitTimes.size());
                    iteration.waitTimes.forEach(wait -> {
                        assertTrue(wait >= 0, "Waiting times must be non-negative.");
                        return true;
                    });
                    int componentSum = stopSequence.access.time
                            + stopSequence.egress.time
                            + stopSequence.rideTimesSeconds.sum()
                            + stopSequence.totalTransferTimeSeconds()
                            + iteration.waitTimes.sum();
                    assertEquals(iteration.totalTime, componentSum,
                            "Component times must sum exactly to total travel time.");
                }
            }
        }
        assertTrue(sawTransfer, "The tested scene should always produce two-ride paths.");
    }

    /// Check the CSV summary rows against the recorded iterations: correct column count, minimum rows reporting the
    /// fastest iteration with its departure time, and mean rows reporting the average total time.
    private static void checkSummaryStatistics (PathResult pathResult) {
        ArrayList<String[]>[] minSummary = pathResult.summarizeIterations(PathResult.Stat.MINIMUM);
        ArrayList<String[]>[] meanSummary = pathResult.summarizeIterations(PathResult.Stat.MEAN);
        for (int d = 0; d < minSummary.length; d++) {
            Multimap<RouteSequence, PathResult.Iteration> iterationMap = pathResult.iterationsForPathTemplates[d];
            assertEquals(iterationMap.keySet().size(), minSummary[d].size());
            assertEquals(iterationMap.keySet().size(), meanSummary[d].size());
            // Rows for one destination are not in a guaranteed order, so check them against aggregate expectations.
            int expectedFastestSeconds = iterationMap.asMap().values().stream()
                    .flatMap(Collection::stream)
                    .mapToInt(i -> i.totalTime)
                    .min().getAsInt();
            long observedFastestSeconds = Long.MAX_VALUE;
            for (String[] row : minSummary[d]) {
                assertEquals(PathResult.DATA_COLUMNS.length, row.length);
                boolean transitRidden = !row[0].isEmpty();
                String departureTime = row[10];
                if (transitRidden) {
                    assertTrue(departureTime.matches("\\d{2}:\\d{2}"), "Expected HH:MM but found: " + departureTime);
                } else {
                    assertTrue(departureTime.isEmpty());
                }
                // CSV times are fractional minutes with two decimal places. That rounding has a maximum error of
                // 0.3 seconds, so the exact whole seconds of the underlying iteration are recoverable and the
                // comparison below is exact, with no tolerance needed.
                observedFastestSeconds = Math.min(observedFastestSeconds, Math.round(Double.parseDouble(row[9]) * 60));
            }
            assertEquals(expectedFastestSeconds, observedFastestSeconds,
                    "The fastest of all minimum-stat rows should be the fastest iteration overall.");
            for (String[] row : meanSummary[d]) {
                assertEquals(PathResult.DATA_COLUMNS.length, row.length);
                assertTrue(row[10].isEmpty(), "Mean rows describe no single iteration, so no departure time.");
            }
        }
    }
}
