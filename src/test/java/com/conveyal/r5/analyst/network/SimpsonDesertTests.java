package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

import java.io.FileOutputStream;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This is a collection of tests using roads and transit lines laid out in a large perfect grid in the desert.
 * These networks have very predictable travel times, so instead of just checking that results don't change from one
 * version to the next of R5 (a form of snapshot testing) this checks that they match theoretically expected travel
 * times given headways, transfer points, distances, common trunks and competing lines, etc.
 *
 * Originally we were using web Mercator gridded destinations, as this was the only option in single point tasks.
 * Because these tests record travel time distributions at the destinations using a large number of Monte Carlo draws,
 * this was doing a lot of work and storing a lot of data for up to thousands of destinations we weren't actually using.
 * Regional tasks with freeform destinations are now used to measure travel times to a limited number of points.
 * This could be extended to use gridded destination PointSets for future tests.
 */
public class SimpsonDesertTests {

    public static final Coordinate SIMPSON_DESERT_CORNER = new CoordinateXY(136.5, -25.5);

    @Test
    public void testGridScheduled () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalRoute(20, 20);
        gridLayout.addHorizontalRoute(40, 20);
        gridLayout.addHorizontalRoute(60, 20);
        gridLayout.addVerticalRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        // Logging and file export for debugging:
        // System.out.println("Grid envelope: " + gridLayout.gridEnvelope());
        // gridLayout.exportFiles("test");

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // Write travel times to Geotiff for debugging visualization in desktop GIS:
        // toGeotiff(oneOriginResult, task);

        // Transit takes 30 seconds per block. Mean wait time is 10 minutes. Any trip takes one transfer.
        // 20+20 blocks at 30 seconds each = 20 minutes. Two waits at 0-20 minutes each, mean is 20 minutes.
        // Board slack is 1 minute. These are not pure frequencies, but synchronized such that the transfer wait is
        // always 10 minutes. So scheduled range is expected to be 1 minute slack, 0-20 minutes wait, 10 minutes ride,
        // 10 minutes wait, 10 minutes ride, giving 31 to 51 minutes.
        // This estimation logic could be better codified as something like TravelTimeEstimate.waitWithHeadaway(20) etc.
        // TODO: For some reason observed is dealyed by 1 minute. Figure out why, perhaps due to integer minute binning.
        Distribution expected = new Distribution(31, 20).delay(1);
        expected.multiAssertSimilar(oneOriginResult.travelTimes, 0);
    }

    /**
     * Similar to above, but using frequency routes which should increase uncertainty waiting for second ride.
     */
    @Test
    public void testGridFrequency () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        // The next two horizontal routes are not expected to contribute to travel time
        gridLayout.addHorizontalFrequencyRoute(40, 20);
        gridLayout.addHorizontalFrequencyRoute(60, 20);
        gridLayout.addVerticalFrequencyRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();
        // int destination = gridLayout.pointIndex(task, 40, 40);
        int destination = 0;

        // Reasoning behind frequency-based travel time is similar to that in the scheduled test method, but transfer
        // time is variable from 0 to 20 minutes. Expected to be 2x 10 minutes riding, with 2x 1-21 minutes waiting
        // (including 1 minute board slack). The result is a triangular distribution tapering up from 22 to 42, and
        // back down to 62.
        Distribution ride = new Distribution(1, 20);
        Distribution expected = Distribution.convolution(ride, ride).delay(20);
        expected.multiAssertSimilar(oneOriginResult.travelTimes, destination);
    }

    /**
     * Similar to frequency case above, but with two different alternative paths.
     * The availability of multiple alternative paths should also reduce the variance of the distribution.
     */
    @Test
    public void testGridFrequencyAlternatives () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        gridLayout.addHorizontalFrequencyRoute(40, 10);
        gridLayout.addVerticalFrequencyRoute(20, 10);
        // gridLayout.addVerticalFrequencyRoute(30, 20); // This route should have no effect on travel time to destination.
        gridLayout.addVerticalFrequencyRoute(40, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(40, 40)
                .monteCarloDraws(10000)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // FIXME convolving new Distribution(2, 10) with itself and delaying 20 minutes is not the same
        //       as convolving new Distribution(2, 10).delay(10) with itself, but it should be.
        Distribution rideA = new Distribution(1, 10).delay(10);
        Distribution rideB = new Distribution(1, 20).delay(10);
        Distribution twoRideAsAndWalk = Distribution.convolution(rideA, rideA);
        Distribution twoRideBsAndWalk = Distribution.convolution(rideB, rideB);
        // TODO identify source of apparent 0.5 minute delay
        Distribution twoAlternatives = Distribution.or(twoRideAsAndWalk, twoRideBsAndWalk).delay(1);

        // Compare expected and actual
        twoAlternatives.multiAssertSimilar(oneOriginResult.travelTimes,0);
    }

    /**
     * For evaluating results from the tests below.
     */
    private static double[] pathTimesAsMinutes (PathResult.PathIterations paths) {
        return paths.iterations.stream().mapToDouble(i -> i.totalTime / 60d).toArray();
    }

    /**
     * Test that the router correctly handles overtaking trips on the same route. Consider Trip A and Trip B, on a
     * horizontal route where each hop time is 30 seconds, except when Trip A slows to 10 minutes/hop for hops 20 and
     * 21. If Trip A leaves the first stop at 7:10 and Trip B leaves the first stop at 7:20, Trip A runs 10 minutes
     * ahead of Trip B until stop 20, is passed around stop 21, and runs 9 minutes behind Trip B for the remaining
     * stops. This example is tested with 3 cases for a rider traveling to stop 42 (well downstream of the overtaking
     * point):
     *
     * 1. Standard rider, departing stop 30 between 7:00 and 7:05, who always rides Trip B (the earliest feasible
     * departure from that stop)
     * 2. Naive rider, departing stop 10 between 7:00 and 7:05, who always rides Trip A (the earliest feasible
     * departure from that stop)
     * 3. Savvy rider, departing stop 10 between 7:13 and 7:18, who always rides Trip B (avoiding boarding the
     * slower Trip A thanks to the "look-ahead" abilities when ENABLE_OPTIMIZATION_RANGE_RAPTOR is true)
     */
    @Test
    public void testOvertakingCases () throws  Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        // TODO refactor this in immutable/fluent style "addScheduledRoute"
        gridLayout.addHorizontalRoute(50);
        gridLayout.routes.get(0).startTimes = new int[] {
                LocalTime.of(7, 10).toSecondOfDay(), // Trip A
                LocalTime.of(7, 20).toSecondOfDay()  // Trip B
        };
        Map<GridRoute.TripHop, Double> hopTimeScaling = new HashMap<>();
        // Trip A slows down from stop 20 to 22, allowing Trip B to overtake it.
        hopTimeScaling.put(new GridRoute.TripHop(0, 20), 20.0);
        hopTimeScaling.put(new GridRoute.TripHop(0, 21), 20.0);
        gridLayout.routes.get(0).hopTimeScaling = hopTimeScaling;
        TransportNetwork network = gridLayout.generateNetwork();

        // 0. Reuse this task builder to produce several tasks. See caveats on build() method.
        GridRegionalTaskBuilder taskBuilder = gridLayout.newTaskBuilder()
                .departureTimeWindow(7, 0, 5)
                .maxRides(1)
                .setOrigin(30, 50)
                .singleFreeformDestination(42, 50);

        // 1. Standard rider: upstream overtaking means Trip B departs origin first and is fastest to destination.
        AnalysisWorkerTask standardRider = taskBuilder.build();
        OneOriginResult standardResult = new TravelTimeComputer(standardRider, network).computeTravelTimes();
        // Trip B departs stop 30 at 7:35. So 30-35 minute wait, plus 7 minute ride.
        Distribution standardExpected = new Distribution(30, 5).delay(7);
        standardExpected.multiAssertSimilar(standardResult.travelTimes, 0);

        // 2. Naive rider: downstream overtaking means Trip A departs origin first but is not fastest to destination.
        AnalysisWorkerTask naiveRider = taskBuilder.setOrigin(10, 50).build();
        OneOriginResult naiveResult = new TravelTimeComputer(naiveRider, network).computeTravelTimes();
        // Trip A departs stop 10 at 7:15. So 10-15 minute wait, plus 36 minute ride.
        Distribution naiveExpected = new Distribution(10, 5).delay(36);
        naiveExpected.multiAssertSimilar(naiveResult.travelTimes, 0);

        // 3. Savvy rider (look-ahead abilities from starting the trip 13 minutes later): waits to board Trip B, even
        // when boarding Trip A is possible
        AnalysisWorkerTask savvyRider = taskBuilder
                .departureTimeWindow(7, 13, 5).build();
        OneOriginResult savvyResult = new TravelTimeComputer(savvyRider, network).computeTravelTimes();
        // Trip B departs stop 10 at 7:25. So 8-13 minute wait, plus 16 minute ride.
        Distribution savvyExpected = new Distribution(8, 5).delay(16);
        savvyExpected.multiAssertSimilar(savvyResult.travelTimes, 0);
    }

    /**
     * Experiments with verifying more complicated distributions.
     */
    @Test
    public void testExperiments () throws Exception {
        GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 100);
        // Common trunk of three routes intersecting another route
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        gridLayout.addHorizontalFrequencyRoute(20, 20);
        gridLayout.addVerticalFrequencyRoute(80, 20);
        TransportNetwork network = gridLayout.generateNetwork();

        AnalysisWorkerTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(20, 20)
                .singleFreeformDestination(80, 80)
                .monteCarloDraws(10000)
                .build();

        OneOriginResult oneOriginResult = new TravelTimeComputer(task, network).computeTravelTimes();

        // Each 60-block ride should take 30 minutes (across and up).
        // One minute board slack, and 20-minute headways.
        Distribution ride = new Distribution(1, 20).delay(30);
        Distribution tripleCommonTrunk = Distribution.or(ride, ride, ride);
        Distribution endToEnd = Distribution.convolution(tripleCommonTrunk, ride);

        // Compare expected and actual
        endToEnd.multiAssertSimilar(oneOriginResult.travelTimes, 0);
    }

    /** Write travel times to GeoTiff. Convenience method to help visualize results in GIS while developing tests. */
    private static void toGeotiff (OneOriginResult oneOriginResult, AnalysisWorkerTask task) {
        try {
            TimeGridWriter timeGridWriter = new TimeGridWriter(oneOriginResult.travelTimes, task);
            timeGridWriter.writeGeotiff(new FileOutputStream("traveltime.tiff"));
        } catch (Exception e) {
            throw new RuntimeException("Could not write geotiff.", e);
        }
    }
}
