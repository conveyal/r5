package com.conveyal.r5.analyst.network;

import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.Modification;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.scenario.AddTrips;
import com.conveyal.r5.analyst.scenario.AdjustSpeed;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
/// The modifications are deserialized from a JSON representation of their UI form, then converted to the R5 backend
/// versions. So this also tests the conversion from segments and speeds as drawn in the UI to backend hop times.
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

    /// This JSON mapper does not mimic the production configuration (JsonUtil.getObjectMapper) because we do not want
    /// its tolerance of unknown properties. Typos in JSON here should fail parsing, not produce default field values.
    /// The modules added to the production mapper should not apply to these test JSON documents.
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            com.conveyal.r5.analyst.scenario.Modification... modifications) {
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

    /// Build a backend AddTripPattern modification for the added north-south route. This is a scheduled (exactTimes)
    /// pattern with a stop at every intersection of the column, drawn as straight two-point segments, at 24 km/h so
    /// each 200 m hop should ideally take 30 seconds. It is built from JSON, which is how it would happen in production
    /// but there is another reason: we will need to change the type of some fields which deserialization will tolerate.
    private static com.conveyal.r5.analyst.scenario.Modification addedColumnRoute () {
        AddTripPattern pattern = (AddTripPattern) modificationFromJson(
                addTripPatternNode("addedColumnRoute", columnCoordinates(1), 24));
        return pattern.toR5();
    }

    /// Coordinates of the added route running down ADDED_ROUTE_COLUMN, one segment per block, with
    /// the given number of collinear line segments subdividing each block.
    private static Coordinate[][] columnCoordinates (int pointsPerBlock) {
        Coordinate[][] segments = new Coordinate[N_BLOCKS][];
        for (int y = 0; y < N_BLOCKS; y++) {
            Coordinate[] coords = new Coordinate[pointsPerBlock + 1];
            for (int k = 0; k <= pointsPerBlock; k++) {
                coords[k] = gridLayout.getPointLatLon(ADDED_ROUTE_COLUMN, y + k / (double) pointsPerBlock);
            }
            segments[y] = coords;
        }
        return segments;
    }

    private static Modification modificationFromJson (ObjectNode node) {
        try {
            return MAPPER.treeToValue(node, Modification.class);
        } catch (Exception e) {
            throw new RuntimeException("Could not deserialize modification JSON.", e);
        }
    }

    private static ObjectNode addTripPatternNode (String name, Coordinate[][] segments, double speedKph) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "add-trip-pattern");
        node.put("name", name);
        node.put("bidirectional", true);
        ArrayNode segmentsNode = node.putArray("segments");
        for (Coordinate[] segment : segments) {
            ObjectNode segmentNode = segmentsNode.addObject();
            segmentNode.put("stopAtStart", true);
            segmentNode.put("stopAtEnd", true);
            segmentNode.put("spacing", 0);
            ObjectNode geometry = segmentNode.putObject("geometry");
            geometry.put("type", "LineString");
            ArrayNode coordinates = geometry.putArray("coordinates");
            for (Coordinate coordinate : segment) {
                coordinates.addArray().add(coordinate.x).add(coordinate.y);
            }
        }
        ObjectNode timetable = node.putArray("timetables").addObject();
        timetable.put("_id", "TT");
        timetable.put("name", "timetable");
        for (String day : new String[] {"monday", "tuesday", "wednesday", "thursday", "friday"}) {
            timetable.put(day, true);
        }
        timetable.put("saturday", false);
        timetable.put("sunday", false);
        timetable.put("startTime", 5 * 3600);
        timetable.put("endTime", 10 * 3600);
        timetable.put("headwaySecs", 600);
        timetable.put("exactTimes", true);
        timetable.put("dwellTime", 0);
        ArrayNode speeds = timetable.putArray("segmentSpeeds");
        for (int i = 0; i < segments.length; i++) {
            speeds.add(speedKph);
        }
        return node;
    }

    /// Target unrounded total riding time along a sequence of segments at the given speed.
    private static double idealPatternSeconds (Coordinate[][] segments, double speedKph) {
        double meters = 0;
        for (Coordinate[] segment : segments) {
            for (int i = 1; i < segment.length; i++) {
                meters += SphericalDistanceLibrary.fastDistance(segment[i - 1], segment[i]);
            }
        }
        return meters / (speedKph * 1000 / 3600);
    }

    /// Converting segments drawn in the UI and their speeds to hop times may require rounding, but the error over a
    /// whole pattern should stay within one second per inter-stop hop. A conversion that truncates once per line
    /// segment rather than rounding once per hop accumulates more error and fails.
    @Test
    public void addTripsHopTimeBound () {
        Coordinate[][] segments = columnCoordinates(10);
        double speedKph = 23; // Chosen so ideal hop times are fractional, making rounding behavior visible.
        AddTripPattern pattern = (AddTripPattern) modificationFromJson(
                addTripPatternNode("roundingPattern", segments, speedKph));
        AddTrips addTrips = pattern.toR5();
        AddTrips.PatternTimetable timetable = addTrips.frequencies.iterator().next();
        int scheduledSeconds = 0;
        for (int hopTime : timetable.hopTimes) {
            scheduledSeconds += hopTime;
        }
        double idealSeconds = idealPatternSeconds(segments, speedKph);
        assertTrue(Math.abs(scheduledSeconds - idealSeconds) <= segments.length, String.format(
                "Scheduled pattern time %d s differs from ideal %.1f s by more than one second per segment (%d segments).",
                scheduledSeconds, idealSeconds, segments.length
        ));
    }

    /// The same hop time bound for the Reroute modification, whose conversion uses the same
    /// segment-to-hop-time machinery. Also expected to fail until hop time truncation fix is merged.
    @Test
    public void rerouteHopTimeBound () {
        Coordinate[][] segments = columnCoordinates(10);
        double speedKph = 23;
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "reroute");
        node.put("name", "roundingReroute");
        node.put("feed", "GRID");
        node.putArray("routes").add("0");
        node.put("dwellTime", 0);
        ArrayNode segmentsNode = node.putArray("segments");
        for (Coordinate[] segment : segments) {
            ObjectNode segmentNode = segmentsNode.addObject();
            segmentNode.put("stopAtStart", true);
            segmentNode.put("stopAtEnd", true);
            segmentNode.put("spacing", 0);
            ObjectNode geometry = segmentNode.putObject("geometry");
            geometry.put("type", "LineString");
            ArrayNode coordinates = geometry.putArray("coordinates");
            for (Coordinate coordinate : segment) {
                coordinates.addArray().add(coordinate.x).add(coordinate.y);
            }
        }
        ArrayNode speeds = node.putArray("segmentSpeeds");
        for (int i = 0; i < segments.length; i++) {
            speeds.add(speedKph);
        }
        com.conveyal.analysis.models.Reroute reroute =
                (com.conveyal.analysis.models.Reroute) modificationFromJson(node);
        com.conveyal.r5.analyst.scenario.Reroute r5Reroute = reroute.toR5();
        int scheduledSeconds = 0;
        for (int hopTime : r5Reroute.hopTimes) {
            scheduledSeconds += hopTime;
        }
        double idealSeconds = idealPatternSeconds(segments, speedKph);
        assertTrue(Math.abs(scheduledSeconds - idealSeconds) <= segments.length, String.format(
                "Rerouted pattern time %d s differs from ideal %.1f s by more than one second per segment (%d segments).",
                scheduledSeconds, idealSeconds, segments.length
        ));
    }

}
