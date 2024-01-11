package com.conveyal.r5.analyst.scenario;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.scenario.FakeGraph.TransitNetwork;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore.Edge;
import com.conveyal.r5.streets.EdgeStore.EdgeFlag;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.Arrays;
import java.util.EnumSet;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.streets.EdgeStore.intToLts;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that Modifications affecting streets work correctly.
 */
public class ModifyStreetsTest {

    private static final double FROM_LAT = 40.0;
    private static final double FROM_LON = -83.0;
    private static final double SIZE_DEGREES = 0.05;
    private static final int TIME_LIMIT_SECONDS = 1200;
    // Analysis bounds
    private static final int MIN_ELEMENTS = 100;

    /**
     * Using a `ModifyStreets` modification, test that adjusting the `walkTimeFactor` appropriately changes travel time.
     * Also indirectly tests that the necessary generalized cost data tables will be added to the network when missing.
     */
    @Test
    public void testGeneralizedCostWalk() {
        var network = FakeGraph.buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_LINES);

        var ms = new ModifyStreets();
        ms.allowedModes = EnumSet.of(StreetMode.WALK);
        ms.walkTimeFactor = 0.1;
        ms.polygons = makeModificationPolygon(FROM_LON, FROM_LAT, SIZE_DEGREES);
        var modifiedNetwork = applySingleModification(network, ms);

        var reachedVertices = getReachedVertices(network);
        var reachedVerticesWithModification = getReachedVertices(modifiedNetwork);

        int nReachedVertices = reachedVertices.size();
        int nReachedVerticesWithModification = reachedVerticesWithModification.size();

        assertTrue(nReachedVertices > 500);
        assertTrue(nReachedVerticesWithModification > nReachedVertices * 2);

        int nLowerTimes = 0;
        for (int v : reachedVertices.keys()) {
            int travelTime = reachedVertices.get(v);
            int travelTimeWithModification = reachedVerticesWithModification.get(v);
            assertTrue(travelTimeWithModification != -1);
            assertTrue(travelTimeWithModification <= travelTime);
            if (travelTimeWithModification != travelTime) {
                nLowerTimes += 1;
                assertTrue(travelTimeWithModification * 1.3 < travelTime);
            }
        }
        assertTrue(nLowerTimes > 500);
    }

    private static TIntIntMap getReachedVertices(TransportNetwork network) {
        var task = new TravelTimeSurfaceTask();
        task.accessModes = EnumSet.of(LegMode.WALK);
        task.directModes = EnumSet.of(LegMode.WALK);
        task.transitModes = EnumSet.noneOf(TransitModes.class);
        task.percentiles = new int[]{50};
        task.fromLat = FROM_LAT;
        task.fromLon = FROM_LON;

        WebMercatorGridPointSet grid = new WebMercatorGridPointSet(network);
        task.north = grid.extents.north;
        task.west = grid.extents.west;
        task.height = grid.extents.height;
        task.width = grid.extents.width;
        task.zoom = grid.extents.zoom;

        var streetRouter = new StreetRouter(network.streetLayer);
        streetRouter.profileRequest = task;
        streetRouter.setOrigin(task.fromLat, task.fromLon);
        streetRouter.streetMode = StreetMode.WALK;
        streetRouter.timeLimitSeconds = TIME_LIMIT_SECONDS;
        streetRouter.route();

        return streetRouter.getReachedVertices();
    }

    /**
     * Test that our LTS labeling process, as well as street modifications, do not set more than one LTS value on a
     * single edge. The LTS is stored as bit flags, so even though LTS values are mutually exclusive it is technically
     * possible (but meaningless) for more than one to be present on the same edge.
     */
    @Test
    public void testLtsRepresentation () {
        TransportNetwork network = buildNetwork(TransitNetwork.SINGLE_LINE);
        checkStreetLayerLts(network.streetLayer);

        ModifyStreets mod = new ModifyStreets();
        mod.allowedModes = EnumSet.of(StreetMode.BICYCLE);
        mod.polygons = makeModificationPolygon(FROM_LON, FROM_LAT, SIZE_DEGREES);
        mod.bikeLts = 1;

        TransportNetwork modifiedNetwork = applySingleModification(network, mod);
        checkStreetLayerLts(modifiedNetwork.streetLayer);

        // Check that the modification set a bunch of edges to have LTS 1.
        int nLtsOneBefore = countLts(network.streetLayer, 1);
        int nLtsOneAfter = countLts(modifiedNetwork.streetLayer, 1);
        assertTrue(nLtsOneAfter > nLtsOneBefore * 1.5);
        assertTrue(nLtsOneAfter > MIN_ELEMENTS);
    }

    private void checkStreetLayerLts(StreetLayer streets) {
        assertTrue(streets.edgeStore.nEdges() > MIN_ELEMENTS);
        int nWithLts = 0;
        for (Edge e = streets.edgeStore.getCursor(0); e.advance(); ) {
            int nSet = 0;
            if (e.getFlag(EdgeFlag.BIKE_LTS_1)) nSet += 1;
            if (e.getFlag(EdgeFlag.BIKE_LTS_2)) nSet += 1;
            if (e.getFlag(EdgeFlag.BIKE_LTS_3)) nSet += 1;
            if (e.getFlag(EdgeFlag.BIKE_LTS_4)) nSet += 1;
            assertTrue(nSet == 0 || nSet == 1);
            if (nSet > 0) nWithLts += 1;
        }
        assertTrue(nWithLts > MIN_ELEMENTS);
    }

    /** @return the number of edges in the supplied street layer that have the given LTS level set. **/
    private int countLts (StreetLayer streets, int lts) {
        EdgeFlag ltsFlag = intToLts(lts);
        int n = 0;
        for (Edge e = streets.edgeStore.getCursor(0); e.advance(); ) {
            if (e.getFlag(ltsFlag)) {
                n += 1;
            }
        }
        return n;
    }


    //// Static utility functions for constructing test modifications and scenarios

    private static TransportNetwork applySingleModification (TransportNetwork baseNetwork, Modification modification) {
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(modification);
        TransportNetwork modifiedNetwork = scenario.applyToTransportNetwork(baseNetwork);
        Assertions.assertEquals(0, modifiedNetwork.scenarioApplicationWarnings.size());
        return modifiedNetwork;
    }

    private static double[][][] makeModificationPolygon (Envelope env) {
        return new double[][][]{{
                {env.getMinX(), env.getMaxY()},
                {env.getMaxX(), env.getMaxY()},
                {env.getMaxX(), env.getMinY()},
                {env.getMinX(), env.getMinY()},
                {env.getMinX(), env.getMaxY()}
        }};
    }

    private static double[][][] makeModificationPolygon (double centerLon, double centerLat, double radiusDegrees) {
        Envelope env = makeEnvelope(centerLon, centerLat, radiusDegrees);
        return makeModificationPolygon(env);
    }

    private static Envelope makeEnvelope (double centerLon, double centerLat, double radiusDegrees) {
        final double west = centerLon - radiusDegrees;
        final double east = centerLon + radiusDegrees;
        final double south = centerLat - radiusDegrees;
        final double north = centerLat + radiusDegrees;
        return new Envelope(west, east, south, north);
    }

}