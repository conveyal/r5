package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.scenario.AddTrips;
import com.conveyal.r5.analyst.scenario.AdjustSpeed;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.analyst.scenario.StopSpec;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;
import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies invariants that scenario application must uphold, thoroughly comparing complete results for networks with
/// different scenarios applied to the same base network. Accessibility is compared at several cutoffs to freeform
/// destinations with power-of-two opportunity counts, which allows exact identification of which opportunities were
/// reached in the manner of a bitset. Tested invariants are:
///
/// - Empty scenarios and modifications that cannot alter or affect any measured path must reproduce baseline results
///   exactly. This aims to catch damage from the shared scenario application machinery, including network copying,
///   transient indexing and distance table rebuilding.
/// - Add-trips modifications should only ever improve results. Travel times must not increase anywhere, and
///   accessibility must not decrease anywhere. Destinations served by new trips must see only benefits.
///
/// All routes are scheduled (exactTimes not frequency-based), so results are deterministic allowing exact equality.
///
/// Following production code paths, every analysis in this class (including the baseline) runs on a scenario copy of
/// the base network.
public class ScenarioInvariantTests {

    private static final int[] CUTOFFS_MINUTES = new int[] {5, 10, 15, 20, 25, 30, 40, 50, 60, 90};
    private static final int ROUTE_ROW = 10;
    private static final int FAR_ROUTE_ROW = 25;
    private static final String FAR_ROUTE_ID = "far";
    private static final int ADDED_ROUTE_COLUMN = 10;
    private static final int N_BLOCKS = 30;

    private static GridLayout gridLayout;
    private static TransportNetwork baseNetwork;
    private static FreeFormPointSet destinationPoints;
    private static int nDestinations;
    private static OneOriginResult baselineResult;

    @BeforeAll
    static void buildNetworkAndBaselineResult () {
        gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, N_BLOCKS);
        gridLayout.addHorizontalRoute(ROUTE_ROW, 10);
        // A second route, out of reach of all measured origin-destination pairs,
        // as the target of the unrelated modification. It is assigned a known route ID so the
        // modification can reference the route without depending on generated identifiers.
        gridLayout.addHorizontalRoute(FAR_ROUTE_ROW, 10).id = FAR_ROUTE_ID;
        baseNetwork = gridLayout.generateNetwork();
        // Destinations with power-of-two opportunity counts, so accessibility sums are bitmasks of reached
        // destinations. The origin is on the first route at (10, 10).
        // The first three lie along that route and gain nothing from the added vertical route. The northern three
        // definitely benefit from it. northFar is beyond both walking range and the base transit network.
        destinationPoints = new FreeFormPointSet(
                new Coordinate[] {
                        gridLayout.getIntersectionLatLon(14, 10), // onRouteEast
                        gridLayout.getIntersectionLatLon(6, 10),  // onRouteWest
                        gridLayout.getIntersectionLatLon(24, 10), // farEast
                        gridLayout.getIntersectionLatLon(10, 16), // northNear
                        gridLayout.getIntersectionLatLon(11, 17), // northMid
                        gridLayout.getIntersectionLatLon(10, 22), // northFar
                },
                new double[] {1, 2, 4, 8, 16, 32}
        );
        nDestinations = destinationPoints.featureCount();
        // Baseline results are computed on an empty scenario, as in production.
        baselineResult = compute(applyScenario("baseline"));
    }

    private static final int NORTH_NEAR = 3;
    private static final int NORTH_FAR = 5;

    /// Apply a scenario containing the given modifications (possibly none) to the base network, returning the
    /// resulting scenario copy. All analyses in this class run on such copies, never on the base network object itself.
    private static TransportNetwork applyScenario (String id,
            Modification... modifications) {
        Scenario scenario = new Scenario();
        scenario.id = id;
        // The modifications list must be mutable because scenario application sorts it in place.
        scenario.modifications = new ArrayList<>(List.of(modifications));
        return scenario.applyToTransportNetwork(baseNetwork);
    }

    /// Compute results for the shared destinations on the given scenario copy network. Analyzing a
    /// network intentionally destructively transposes the egress cost tables of its linkages.
    /// Those tables are large, and the untransposed form is not needed during analysis, so it is
    /// released for garbage collection. However, scenario application copies entries from the base
    /// network's untransposed tables, so a network that has been analyzed directly can no longer
    /// have scenarios applied to it. Production therefore analyzes only scenario copies, applying
    /// an empty scenario for baseline cases. This class follows the same pattern.
    private static OneOriginResult compute (TransportNetwork scenarioNetwork) {
        RegionalTask task = gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(10, 10)
                .recordAccessibility()
                .cutoffsMinutes(CUTOFFS_MINUTES)
                .freeformDestinations(destinationPoints)
                .buildRegional();
        return new TravelTimeComputer(task, scenarioNetwork).computeTravelTimes();
    }

    private static void assertIdenticalResults (OneOriginResult expected, OneOriginResult actual) {
        int[][] expectedTimes = expected.travelTimes.getValues();
        int[][] actualTimes = actual.travelTimes.getValues();
        for (int p = 0; p < expectedTimes.length; p++) {
            for (int d = 0; d < nDestinations; d++) {
                assertEquals(expectedTimes[p][d], actualTimes[p][d],
                        String.format("Travel time changed at destination %d, percentile index %d.", d, p));
            }
        }
        int[][][] expectedAccess = expected.accessibility.getIntValues();
        int[][][] actualAccess = actual.accessibility.getIntValues();
        for (int p = 0; p < expectedAccess[0].length; p++) {
            for (int c = 0; c < expectedAccess[0][p].length; c++) {
                assertEquals(expectedAccess[0][p][c], actualAccess[0][p][c],
                        String.format("Accessibility changed at percentile index %d, cutoff index %d.", p, c));
            }
        }
    }

    /// Two independently created empty-scenario copies of the same base network must produce identical
    /// results and not leak state. The baseline itself is computed on an empty-scenario copy.
    @Test
    public void emptyScenario () {
        assertIdenticalResults(baselineResult, compute(applyScenario("empty")));
    }

    /// Modifying a route that should not be used by any trip we examine should exactly reproduce base results.
    /// The far northern route is beyond walking range of the origin, and no other route connects to it.
    @Test
    public void unrelatedModification () {
        AdjustSpeed adjustSpeed = new AdjustSpeed();
        // R5 modifications reference GTFS entities by feed-scoped IDs.
        adjustSpeed.routes = Set.of(GridGtfsGenerator.FEED_ID + ":" + FAR_ROUTE_ID);
        adjustSpeed.scale = 0.5;
        assertIdenticalResults(baselineResult, compute(applyScenario("unrelated", adjustSpeed)));
    }

    /// Adding a scheduled north-south route through the origin should not make any travel time
    /// longer or any accessibility figure smaller, and should improve figures at the northern
    /// destinations. northNear is only reachable by walking in the base network, and northFar is unreachable.
    @Test
    public void addTripsImproves () {
        OneOriginResult withRoute = compute(applyScenario("addTrips", addedColumnRoute()));

        int[][] baseTimes = baselineResult.travelTimes.getValues();
        int[][] newTimes = withRoute.travelTimes.getValues();
        for (int p = 0; p < baseTimes.length; p++) {
            for (int d = 0; d < nDestinations; d++) {
                assertTrue(newTimes[p][d] <= baseTimes[p][d],
                        String.format("Added trips increased a travel time at destination %d, percentile index %d.", d, p));
            }
            assertTrue(newTimes[p][NORTH_NEAR] < baseTimes[p][NORTH_NEAR],
                    "Added trips should strictly improve northNear at every percentile.");
            assertEquals(UNREACHED, baseTimes[p][NORTH_FAR], "northFar should be unreachable in the base network.");
            assertTrue(newTimes[p][NORTH_FAR] != UNREACHED, "Added trips should make northFar reachable.");
        }

        int[][][] baseAccess = baselineResult.accessibility.getIntValues();
        int[][][] newAccess = withRoute.accessibility.getIntValues();
        boolean strictlyGreater = false;
        for (int p = 0; p < baseAccess[0].length; p++) {
            for (int c = 0; c < baseAccess[0][p].length; c++) {
                assertTrue(newAccess[0][p][c] >= baseAccess[0][p][c],
                        String.format("Added trips decreased accessibility at percentile index %d, cutoff index %d.", p, c));
                if (newAccess[0][p][c] > baseAccess[0][p][c]) {
                    strictlyGreater = true;
                }
            }
        }
        assertTrue(strictlyGreater, "Added trips should strictly increase accessibility somewhere.");
    }

    /// Build an AddTrips modification for the added north-south route. This is a scheduled (exactTimes) pattern with a
    /// stop at every intersection of the column at 24 km/h, so each 200 m hop should ideally take 30 seconds.
    private static AddTrips addedColumnRoute () {
        double metersPerSecond = 24 * 1000 / 3600.0;
        AddTrips addTrips = new AddTrips();
        addTrips.comment = "addedColumnRoute";
        addTrips.bidirectional = true;
        addTrips.stops = new ArrayList<>();
        Coordinate previous = null;
        List<Integer> hopTimes = new ArrayList<>();
        for (int y = 0; y <= N_BLOCKS; y++) {
            Coordinate stop = gridLayout.getPointLatLon(ADDED_ROUTE_COLUMN, y);
            addTrips.stops.add(new StopSpec(stop.x, stop.y));
            if (previous != null) {
                double meters = SphericalDistanceLibrary.fastDistance(previous, stop);
                hopTimes.add((int) Math.round(meters / metersPerSecond));
            }
            previous = stop;
        }
        AddTrips.PatternTimetable timetable = new AddTrips.PatternTimetable();
        timetable.entryId = "TT";
        timetable.monday = timetable.tuesday = timetable.wednesday = timetable.thursday = timetable.friday = true;
        timetable.hopTimes = hopTimes.stream().mapToInt(Integer::intValue).toArray();
        timetable.dwellTimes = new int[addTrips.stops.size()];
        // Scheduled departures every 10 minutes from 05:00 to 10:00 inclusive.
        timetable.firstDepartures = IntStream.rangeClosed(0, 30).map(i -> 5 * 3600 + i * 600).toArray();
        addTrips.frequencies = List.of(timetable);
        return addTrips;
    }

}
