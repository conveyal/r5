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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This is a collection of tests using roads and transit lines laid out in a large perfect grid in the desert.
 * These networks have very predictable travel times, so instead of just checking that results don't change from one
 * version to the next of R5 (a form of snapshot testing) this checks that they match theoretically expected travel
 * times given headways, transfer points, distances, common trunks and competing lines, etc.
 *
 * TODO Option to align street grid exactly with sample points in WebMercatorGridPointSet to eliminate walking time
 *      between origin and destination and transit stops or street intersections. Also check splitting.
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
                .morningPeak()
                .setOrigin(20, 20)
                .uniformOpportunityDensity(10)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();

        // Write travel times to Geotiff for debugging visualization in desktop GIS:
        // toGeotiff(oneOriginResult, task);

        int destination = gridLayout.pointIndex(task, 40, 40);
        int[] travelTimePercentiles = oneOriginResult.travelTimes.getTarget(destination);

        // Transit takes 30 seconds per block. Mean wait time is 10 minutes. Any trip takes one transfer.
        // 20+20 blocks at 30 seconds each = 20 minutes. Two waits at 0-20 minutes each, mean is 20 minutes.
        // Board slack is 2 minutes. With pure frequency routes total should be 24 to 64 minutes, median 44.
        // However, these are not pure frequencies, but synchronized such that the transfer wait is always 10 minutes.
        // So scheduled range is expected to be 2 minutes slack, 0-20 minutes wait, 10 minutes ride, 10 minutes wait,
        // 10 minutes ride, giving 32 to 52 minutes.
        // Maybe codify this estimation logic as a TravelTimeEstimate.waitWithHeadaway(20) etc.
        DistributionTester.assertUniformlyDistributed(travelTimePercentiles, 32, 52);

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
                .morningPeak()
                .setOrigin(20, 20)
                .uniformOpportunityDensity(10)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();
        int destination = gridLayout.pointIndex(task, 40, 40);
        int[] travelTimePercentiles = oneOriginResult.travelTimes.getTarget(destination);

        // Frequency travel time reasoning is similar to scheduled test method.
        // But transfer time is variable from 0...20 minutes.
        // Frequency range is expected to be 2x 2 minutes slack, 2x 0-20 minutes wait, 2x 10 minutes ride,
        // giving 24 to 64 minutes.
        Distribution ride = new Distribution(2, 20);
        Distribution expected = Distribution.convolution(ride, ride).delay(20);

        DistributionTester.assertExpectedDistribution(expected, travelTimePercentiles);
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
                .morningPeak()
                .setOrigin(20, 20)
                .uniformOpportunityDensity(10)
                .monteCarloDraws(20000)
                .build();

        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();
        int destination = gridLayout.pointIndex(task, 40, 40);
        int[] travelTimePercentiles = oneOriginResult.travelTimes.getTarget(destination);

        // FIXME convolving new Distribution(2, 10) with itself and delaying 20 minutes is not the same
        //       as convolving new Distribution(2, 10).delay(10) with itself, but it should be.
        Distribution rideA = new Distribution(2, 10).delay(10);
        Distribution rideB = new Distribution(2, 20).delay(10);
        Distribution twoRideAsAndWalk = Distribution.convolution(rideA, rideA);
        Distribution twoRideBsAndWalk = Distribution.convolution(rideB, rideB);
        Distribution twoAlternatives = Distribution.or(twoRideAsAndWalk, twoRideBsAndWalk).delay(3);

        // Compare expected and actual
        Distribution observed = Distribution.fromTravelTimeResult(oneOriginResult.travelTimes, destination);

        twoAlternatives.assertSimilar(observed);
        DistributionTester.assertExpectedDistribution(twoAlternatives, travelTimePercentiles);
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

        // 1. Standard rider: upstream overtaking means Trip B departs origin first and is fastest to destination.
        AnalysisWorkerTask standardRider = gridLayout.newTaskBuilder()
                .departureTimeWindow(7, 0, 5)
                .maxRides(1)
                .setOrigin(30, 50)
                .setDestination(42, 50)
                .uniformOpportunityDensity(10)
                .build();

        OneOriginResult standardResult = new TravelTimeComputer(standardRider, network).computeTravelTimes();
        List<PathResult.PathIterations> standardPaths = standardResult.paths.getPathIterationsForDestination();
        int[] standardTimes = standardPaths.get(0).iterations.stream().mapToInt(i -> (int) i.totalTime).toArray();
        // Trip B departs stop 30 at 7:35. So 30-35 minute wait, plus ~5 minute ride and ~5 minute egress leg
        assertTrue(Arrays.equals(new int[]{45, 44, 43, 42, 41}, standardTimes));

        // 2. Naive rider: downstream overtaking means Trip A departs origin first but is not fastest to destination.
        AnalysisWorkerTask naiveRider = gridLayout.copyTask(standardRider)
                .setOrigin(10, 50)
                .build();

        OneOriginResult naiveResult = new TravelTimeComputer(naiveRider, network).computeTravelTimes();
        List<PathResult.PathIterations> naivePaths = naiveResult.paths.getPathIterationsForDestination();
        int[] naiveTimes = naivePaths.get(0).iterations.stream().mapToInt(i -> (int) i.totalTime).toArray();
        // Trip A departs stop 10 at 7:15. So 10-15 minute wait, plus ~35 minute ride and ~5 minute egress leg
        assertTrue(Arrays.equals(new int[]{54, 53, 52, 51, 50}, naiveTimes));

        // 3. Savvy rider (look-ahead abilities from starting the trip 13 minutes later): waits to board Trip B, even
        // when boarding Trip A is possible
        AnalysisWorkerTask savvyRider = gridLayout.copyTask(naiveRider)
                .departureTimeWindow(7, 13, 5)
                .build();

        OneOriginResult savvyResult = new TravelTimeComputer(savvyRider, network).computeTravelTimes();
        List<PathResult.PathIterations> savvyPaths = savvyResult.paths.getPathIterationsForDestination();
        int[] savvyTimes = savvyPaths.get(0).iterations.stream().mapToInt(i -> (int) i.totalTime).toArray();
        // Trip B departs stop 10 at 7:25. So 8-12 minute wait, plus ~16 minute ride and ~5 minute egress leg
        assertTrue(Arrays.equals(new int[]{32, 31, 30, 29, 28}, savvyTimes));
    }

    /**
     * Experiments
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
                .morningPeak()
                .setOrigin(20, 20)
                .uniformOpportunityDensity(10)
                .monteCarloDraws(4000)
                .build();

        OneOriginResult oneOriginResult = new TravelTimeComputer(task, network).computeTravelTimes();
        int pointIndex = gridLayout.pointIndex(task, 80, 80);
        int[] travelTimePercentiles = oneOriginResult.travelTimes.getTarget(pointIndex);

        // Each 60-block ride should take 30 minutes (across and up).
        // Two minutes board slack, and 20-minute headways. Add one minute walking.
        Distribution ride = new Distribution(2, 20).delay(30);
        Distribution tripleCommonTrunk = Distribution.or(ride, ride, ride);
        Distribution endToEnd = Distribution.convolution(tripleCommonTrunk, ride);

        // Compare expected and actual
        Distribution observed = Distribution.fromTravelTimeResult(oneOriginResult.travelTimes, pointIndex);

        endToEnd.assertSimilar(observed);
        DistributionTester.assertExpectedDistribution(endToEnd, travelTimePercentiles);
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
