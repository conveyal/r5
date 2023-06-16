package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;

public class RandomFrequencyPhasingTests {

    /**
     * This recreates the problem in issue #740, inspired by the structure of STM Montréal GTFS métro schedules.
     * This involves a pattern with two different frequency trips on two different services. The first service has
     * N freq entries, and the second has more than N entries.
     *
     * Here we make one weekday trip with a single frequency entry, and one weekend trip with two frequency entries.
     * We search on the weekend, causing the weekday trip to be filtered out. The Monte Carlo boarding code expects
     * an array of two offsets for the weekend trip, but sees a single offset intended for the weekday trip. In addition
     * the offsets generated for the weekday service will be in the range (0...30) while the headway of the weekend
     * service is much shorter - many generated offsets will exceed the headway. So at least two different assertions
     * can fail here, but whether they will actually fail depends on the order of the TripSchedules in the TripPattern
     * (the weekend trip must come after the weekday one, so filtering causes a decrease in trip index). This depends
     * in turn on the iteration order of the GtfsFeed.trips map, which follows the natural order of its keys so should
     * be determined by the lexical order of trip IDs, which are determined by the order in which timetables are added
     * to the GridRoute.
     *
     * The problem exists even if the mismatched trips have the same number of entries but can fail silently as offsets
     * are computed for the wrong headways. The output travel time range will even be correct, but the distribution will
     * be skewed by the offsets being randomly selected from a different headway, while the headway itself is accurate.
     *
     * The structure created here can only be created by input GTFS, and cannot be produced by our Conveyal scenarios.
     * In scenarios, we always produce exactly one frequency entry per trip. This does not mean scenarios are immune
     * to the problem of using offsets from the wrong trip - it's just much harder to detect the problem as all arrays
     * are the same length, so it can fail silently.
     */
    @Test
    public void testFilteredTripRandomization () throws Exception {

        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 40);
        // TODO DSL revision:
        // gridLayout.newRoute(/*construct and add to routes*/).horizontal(20).addTimetable()...
        gridLayout.routes.add(GridRoute
                .newHorizontalRoute(gridLayout, 20, 30)
                .addTimetable(GridRoute.Services.WEEKEND, 6, 10, 4)
                .addTimetable(GridRoute.Services.WEEKEND, 10, 22, 8)
                .pureFrequency()
        );
        TransportNetwork network = gridLayout.generateNetwork();
        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekendMorningPeak()
                .setOrigin(20, 20)
                .monteCarloDraws(1000)
                .uniformOpportunityDensity(10)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

    }

}
