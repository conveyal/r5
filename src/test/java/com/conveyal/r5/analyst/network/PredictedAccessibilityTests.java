package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;
import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares accessibility figures and travel times against values predicted from first principles, for freeform
/// destinations including points mid-block and entirely off the street network. Each destination's opportunity
/// count is a distinct power of two, and accessibility is a sum, so a bitmask identifying exactly which destinations
/// were reached.
///
/// The network is a single scheduled route on a street grid, with no frequency routes, so every travel time at every
/// departure minute should be deterministic. The prediction assumes ideal Manhattan street distances, with block
/// lengths, walking speed, and transit hop times taken from the test scene configuration, a board wait of at least
/// 60 seconds, and percentile extraction over the departure minutes of the window.
///
/// Actual travel times will differ from the ideal predictions by a small, bounded amount, as street distances derive
/// from projected fixed-point coordinates and times are truncated to integer seconds. Linking stops and points to the
/// street network adds small walking times. Those results are rounded to minutes, and tests accept any number of
/// minutes that could correspond to a value in our acceptable range of seconds.
///
/// Travel time cutoffs that fall inside any destination's tolerance range are not used, ensuring no uncertainty
/// in expected accessibility sums.
///
/// On this idealized network the prediction itself is only a few lines of integer arithmetic: block counts times
/// traversal times and schedule lookups. Most of the remaining complexity in this class accommodates imprecision
/// in the system being tested. If lack was removed or made more observable (for example by retaining second-resolution
/// travel times for tests), we could move back toward the trivial model the simplified network was designed to allow.
public class PredictedAccessibilityTests {

    // Test configuration chosen freely to suit this set of tests.

    private static final int N_BLOCKS = 30;
    private static final int ROUTE_ROW = 10;
    private static final int HEADWAY_SECONDS = 10 * 60;
    private static final int ORIGIN_X = 10;
    private static final int ORIGIN_Y = 10;

    private static final GridLayout gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, N_BLOCKS);

    // Values derived from general-purpose grid classes so predictions automatically track defaults.

    private static final double BLOCK_METERS = gridLayout.streetGridSpacingMeters;
    private static final double WALK_METERS_PER_SECOND = BLOCK_METERS / gridLayout.walkBlockTraversalTimeSeconds;
    private static final int TRANSIT_SECONDS_PER_BLOCK = gridLayout.transitBlockTraversalTimeSeconds;
    private static final int SERVICE_START_SECONDS = GridRoute.DEFAULT_SERVICE_START_HOUR * 3600;
    private static final int SERVICE_END_SECONDS = GridRoute.DEFAULT_SERVICE_END_HOUR * 3600;
    private static final int WINDOW_START_SECONDS = GridTaskBuilder.MORNING_PEAK_START.toSecondOfDay();
    private static final int WINDOW_MINUTES =
            (GridTaskBuilder.MORNING_PEAK_END.toSecondOfDay() - WINDOW_START_SECONDS) / 60;

    // Expected behaviors of the routing code fixed when these tests were authored. These are deliberately restated
    // as literals rather than referenced from production code, keeping predictions independent of the code being
    // tested so a change in behaviors causes tests to fail and forces a conscious decision.

    /// Mirrors FastRaptorWorker.MINIMUM_BOARD_WAIT_SEC (private).
    private static final int BOARD_WAIT_SECONDS = 60;

    /// Mirrors the default value of ProfileRequest.maxWalkTime, in seconds.
    private static final int MAX_LEG_WALK_SECONDS = 30 * 60;

    /// Mirrors TransitLayer.WALK_DISTANCE_LIMIT_METERS.
    private static final int EGRESS_DISTANCE_CAP_METERS = 2000;

    /// Actual travel times may be at most this much shorter than predicted.
    private static final int TOLERANCE_SHORTER_SECONDS = 3;

    /// Actual travel times may be at most this much longer than predicted
    /// (positive walking overhead from linking stops, origins, and destination points to the street network).
    private static final int TOLERANCE_LONGER_SECONDS = 25;

    /// Departures at exactly the minimum board wait are unboardable in practice, because link traversal delays
    /// arrival at the stop. This extra wait reproduces that link time.
    /// Any value in (0, 10) selects the same trips, as feasible waits on this network differ by multiples of 10 s.
    private static final int BOARD_TIE_BREAK_SECONDS = 1;

    private static TransportNetwork network;
    private static List<Destination> destinations;
    private static List<double[]> predictedPercentileSeconds; // per destination, per percentile
    private static int[] cutoffsMinutes;
    private static OneOriginResult result;

    /// A destination point described by the street it should link to and the split point (splitX, splitY), in block
    /// units, where it should link along that street. The point itself sits offsetMeters from the split point.
    /// Walking distance from any intersection is then the Manhattan street distance to the split point plus the
    /// perpendicular offset. Use the static factory methods, whose parameter types enforce rules at compile time.
    private record Destination(String label, GridRoute.Orientation street, double splitX, double splitY,
                               double offsetMeters, int opportunities) {

        Destination {
            double along = (street == GridRoute.Orientation.HORIZONTAL) ? splitX : splitY;
            double across = (street == GridRoute.Orientation.HORIZONTAL) ? splitY : splitX;
            checkArgument(across == Math.floor(across),
                    "The coordinate across the street must be a whole number of blocks.");
            if (offsetMeters > 0) {
                double alongFraction = along - Math.floor(along);
                double nearestCompetingStreetMeters = Math.min(
                        Math.min(alongFraction, 1 - alongFraction) * BLOCK_METERS, // crossing streets
                        BLOCK_METERS / 2 // midpoint between the intended street and its parallel neighbor
                );
                checkArgument(offsetMeters < nearestCompetingStreetMeters,
                        "Offset point would lie closer to another street than to its intended split point.");
            }
        }

        static Destination atIntersection (String label, int x, int y, int opportunities) {
            return new Destination(label, GridRoute.Orientation.HORIZONTAL, x, y, 0, opportunities);
        }

        static Destination onHorizontalStreet (String label, double x, int y, double offsetMeters, int opportunities) {
            return new Destination(label, GridRoute.Orientation.HORIZONTAL, x, y, offsetMeters, opportunities);
        }

        static Destination onVerticalStreet (String label, int x, double y, double offsetMeters, int opportunities) {
            return new Destination(label, GridRoute.Orientation.VERTICAL, x, y, offsetMeters, opportunities);
        }

        double walkMetersFrom (double fromX, double fromY) {
            double blocks = Math.abs(fromX - splitX) + Math.abs(fromY - splitY);
            return blocks * BLOCK_METERS + offsetMeters;
        }

        double walkSecondsFrom (double fromX, double fromY) {
            return walkMetersFrom(fromX, fromY) / WALK_METERS_PER_SECOND;
        }

        double directWalkSeconds () {
            return walkSecondsFrom(ORIGIN_X, ORIGIN_Y);
        }

        double egressWalkMeters (int alightStop) {
            return walkMetersFrom(alightStop, ROUTE_ROW);
        }

        /// The geographic location of this point, displaced perpendicular to its street from the
        /// split point (eastward from vertical streets, northward from horizontal ones).
        Coordinate coordinate () {
            double blocksOffset = offsetMeters / BLOCK_METERS;
            double x = splitX + (street == GridRoute.Orientation.VERTICAL ? blocksOffset : 0);
            double y = splitY + (street == GridRoute.Orientation.HORIZONTAL ? blocksOffset : 0);
            return gridLayout.getPointLatLon(x, y);
        }
    }

    @BeforeAll
    static void buildNetworkAndPredict () {
        gridLayout.addHorizontalRoute(ROUTE_ROW, HEADWAY_SECONDS / 60);
        network = gridLayout.generateNetwork();
        destinations = List.of(
                // On-route intersection close enough that walking competes with riding.
                Destination.atIntersection("rideEast", 14, ROUTE_ROW, 1),
                // On-route intersection beyond walking range, reachable only by transit.
                Destination.atIntersection("rideFarEast", 24, ROUTE_ROW, 2),
                // Mid-block point on the route's own street, west of the origin (ridden in the other direction).
                Destination.onHorizontalStreet("midBlockOnRoute", 6.4, ROUTE_ROW, 0, 4),
                // Mid-block point 90 m off the route's street (splitting plus off-street linking).
                Destination.onHorizontalStreet("midBlockOffset", 16.5, ROUTE_ROW, 90, 8),
                // Intersection three blocks off the route.
                Destination.atIntersection("offRouteIntersection", 14, 13, 16),
                // Mid-block point 40m off a vertical street (split on a street perpendicular to the route).
                Destination.onVerticalStreet("midBlockVertical", 14, 12.5, 40, 32)
        );

        predictedPercentileSeconds = new ArrayList<>();
        for (Destination destination : destinations) {
            predictedPercentileSeconds.add(percentiles(predictedSecondsPerMinute(destination)));
        }
        cutoffsMinutes = choosePartitioningCutoffs();

        FreeFormPointSet pointSet = new FreeFormPointSet(
                destinations.stream().map(Destination::coordinate).toArray(Coordinate[]::new),
                destinations.stream().mapToDouble(Destination::opportunities).toArray()
        );
        RegionalTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(ORIGIN_X, ORIGIN_Y)
                .recordAccessibility()
                .cutoffsMinutes(cutoffsMinutes)
                .freeformDestinations(pointSet)
                .buildRegional();
        result = new TravelTimeComputer(task, network).computeTravelTimes();
    }

    /// Predicted travel time in seconds for each departure minute of the window, for one destination: the better of
    /// walking directly and riding the route, enumerating all boarding stops, alighting stops, and directions.
    private static double[] predictedSecondsPerMinute (Destination destination) {
        double[] times = new double[WINDOW_MINUTES];
        double directWalk = destination.directWalkSeconds();
        for (int m = 0; m < WINDOW_MINUTES; m++) {
            int departFromOrigin = WINDOW_START_SECONDS + m * 60;
            double best = directWalk <= MAX_LEG_WALK_SECONDS ? directWalk : Double.POSITIVE_INFINITY;
            for (int direction : new int[] {1, -1}) {
                for (int board = 0; board <= N_BLOCKS; board++) {
                    double accessWalk = Math.abs(ORIGIN_X - board) * BLOCK_METERS / WALK_METERS_PER_SECOND;
                    if (accessWalk > MAX_LEG_WALK_SECONDS) continue;
                    double earliestBoardable = departFromOrigin + accessWalk
                            + BOARD_WAIT_SECONDS + BOARD_TIE_BREAK_SECONDS;
                    // Forward trips depart the west end at each service start time, backward trips the east end.
                    int stopOffset = (direction > 0 ? board : N_BLOCKS - board) * TRANSIT_SECONDS_PER_BLOCK;
                    double firstFeasibleStart = SERVICE_START_SECONDS + HEADWAY_SECONDS *
                            Math.max(0, Math.ceil((earliestBoardable - stopOffset - SERVICE_START_SECONDS) / HEADWAY_SECONDS));
                    if (firstFeasibleStart >= SERVICE_END_SECONDS) continue;
                    double boardDeparture = firstFeasibleStart + stopOffset;
                    for (int alight = 0; alight <= N_BLOCKS; alight++) {
                        if (direction > 0 ? alight <= board : alight >= board) continue;
                        double egressMeters = destination.egressWalkMeters(alight);
                        double egressSeconds = egressMeters / WALK_METERS_PER_SECOND;
                        if (egressSeconds > MAX_LEG_WALK_SECONDS || egressMeters > EGRESS_DISTANCE_CAP_METERS) continue;
                        double arrival = boardDeparture
                                + Math.abs(alight - board) * TRANSIT_SECONDS_PER_BLOCK + egressSeconds;
                        best = Math.min(best, arrival - departFromOrigin);
                    }
                }
            }
            times[m] = best;
        }
        return times;
    }

    /// Extract the test percentiles from per-minute travel times,
    /// using the same non-interpolating definition as TravelTimeReducer#findPercentileIndex.
    private static double[] percentiles (double[] perMinuteSeconds) {
        double[] sorted = perMinuteSeconds.clone();
        Arrays.sort(sorted);
        double[] out = new double[DistributionTester.PERCENTILES.length];
        for (int p = 0; p < out.length; p++) {
            int index = (int) (Math.ceil(DistributionTester.PERCENTILES[p] / 100d * sorted.length) - 1);
            out[p] = sorted[index];
        }
        return out;
    }

    /// Choose up to 12 cutoffs (the regional task maximum) that fall outside the tolerance range of every
    /// predicted travel time, such that each value is unambiguously classified as within or beyond every
    /// cutoff. Among the usable candidates, keep only those where the predicted membership in different
    /// cutoffs and percentiles changes to maximize distinct partitioning of destinations.
    private static int[] choosePartitioningCutoffs () {
        List<Integer> distinct = new ArrayList<>();
        String previousPattern = null;
        for (int cutoff = 1; cutoff <= 120; cutoff++) {
            int cutoffSeconds = cutoff * 60;
            boolean unambiguous = predictedPercentileSeconds.stream()
                    .flatMapToDouble(Arrays::stream)
                    .allMatch(t -> t - TOLERANCE_SHORTER_SECONDS >= cutoffSeconds
                            || t + TOLERANCE_LONGER_SECONDS < cutoffSeconds);
            if (!unambiguous) continue;
            StringBuilder pattern = new StringBuilder();
            for (double[] byPercentile : predictedPercentileSeconds) {
                for (double t : byPercentile) {
                    pattern.append(t < cutoffSeconds ? '1' : '0');
                }
            }
            if (!pattern.toString().equals(previousPattern)) {
                distinct.add(cutoff);
                previousPattern = pattern.toString();
            }
        }
        int nChosen = Math.min(12, distinct.size());
        int[] chosen = new int[nChosen];
        for (int i = 0; i < nChosen; i++) {
            chosen[i] = distinct.get((int) Math.round(i * (distinct.size() - 1) / (double) (nChosen - 1)));
        }
        return chosen;
    }

    /// Check preconditions on which other assertions rely. Every destination is always reachable. Direct walk
    /// times do not hit the walk time cap (pruning would make walking option availability unpredictable).
    /// Enough unambiguous cutoffs exist, and at least one chosen cutoff separates destinations into reachable
    /// and unreachable so accessibility sums are not all-or-nothing.
    @Test
    public void preconditions () {
        for (int d = 0; d < destinations.size(); d++) {
            Destination destination = destinations.get(d);
            for (double t : predictedPercentileSeconds.get(d)) {
                assertTrue(Double.isFinite(t), destination.label + " should be reachable at all percentiles.");
            }
            double directWalk = destination.directWalkSeconds();
            assertTrue(directWalk < MAX_LEG_WALK_SECONDS - 300 || directWalk > MAX_LEG_WALK_SECONDS + 600,
                    destination.label + " direct walk time is too close to the walk time cap.");
        }
        assertTrue(cutoffsMinutes.length >= 4, "Too few cutoffs are clear of predicted travel times.");
        boolean somePartition = false;
        for (int cutoff : cutoffsMinutes) {
            long reachable = predictedPercentileSeconds.stream()
                    .filter(byPercentile -> byPercentile[2] < cutoff * 60)
                    .count();
            if (reachable > 0 && reachable < destinations.size()) {
                somePartition = true;
                break;
            }
        }
        assertTrue(somePartition, "No cutoff separates the destinations at the median percentile.");
    }

    /// Travel times to each destination must match predictions at integer minute resolution.
    /// For most predictions the tolerance range is within a single minute and the assertion is exact.
    @Test
    public void percentileTravelTimes () {
        for (int d = 0; d < destinations.size(); d++) {
            Destination destination = destinations.get(d);
            int[] actualMinutes = result.travelTimes.getTarget(d);
            for (int p = 0; p < DistributionTester.PERCENTILES.length; p++) {
                double predicted = predictedPercentileSeconds.get(d)[p];
                int lowestMinute = (int) ((predicted - TOLERANCE_SHORTER_SECONDS) / 60);
                int highestMinute = (int) ((predicted + TOLERANCE_LONGER_SECONDS) / 60);
                int actual = actualMinutes[p];
                assertTrue(actual >= lowestMinute && actual <= highestMinute, String.format(
                        "%s at percentile %d: predicted %.0f seconds (minutes %d to %d), recorded minute %d.",
                        destination.label, DistributionTester.PERCENTILES[p],
                        predicted, lowestMinute, highestMinute, actual
                ));
            }
        }
    }

    /// Accessibility at each percentile and cutoff must equal the sum of the power-of-two
    /// opportunity counts of exactly those destinations predicted to be within the cutoff. Because
    /// each count corresponds to a distinct bit position, the sum is a bitmask of the reached
    /// destinations. A mismatch can be decoded into which destinations differ from the prediction.
    @Test
    public void accessibilitySums () {
        int[][][] accessibility = result.accessibility.getIntValues();
        for (int p = 0; p < DistributionTester.PERCENTILES.length; p++) {
            for (int c = 0; c < cutoffsMinutes.length; c++) {
                int expected = 0;
                for (int d = 0; d < destinations.size(); d++) {
                    if (predictedPercentileSeconds.get(d)[p] < cutoffsMinutes[c] * 60) {
                        expected += destinations.get(d).opportunities();
                    }
                }
                int actual = accessibility[0][p][c];
                assertEquals(expected, actual, String.format(
                        "Accessibility at percentile %d, cutoff %d minutes; differing destinations: %s.",
                        DistributionTester.PERCENTILES[p], cutoffsMinutes[c], differingDestinations(expected, actual)
                ));
            }
        }
    }

    /// Name the destinations represented by bits which differ between an expected and an actual accessibility value.
    /// Bits beyond the known weights indicate weight is not a sum of known destinations, so are reported.
    private static String differingDestinations (int expected, int actual) {
        int difference = expected ^ actual;
        List<String> labels = new ArrayList<>();
        for (Destination destination : destinations) {
            if ((difference & destination.opportunities()) != 0) {
                boolean expectedIn = (expected & destination.opportunities()) != 0;
                labels.add(destination.label() + (expectedIn ? " (missing)" : " (unexpected)"));
            }
            difference &= ~destination.opportunities();
        }
        if (difference != 0) {
            labels.add("unrecognized bits " + Integer.toBinaryString(difference));
        }
        return labels.isEmpty() ? "none" : String.join(", ", labels);
    }

}
