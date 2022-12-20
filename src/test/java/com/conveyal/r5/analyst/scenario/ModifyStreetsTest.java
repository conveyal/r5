package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that Modifications affecting streets work correctly.
 */
public class ModifyStreetsTest {

    final double FROM_LAT = 40.0;
    final double FROM_LON = -83.0;
    final int TIME_LIMIT_SECONDS = 1200;
    // Analysis bounds
    final double SIZE_DEGREES = 0.05;
    final double WEST = FROM_LON - SIZE_DEGREES;
    final double EAST = FROM_LON + SIZE_DEGREES;
    final double SOUTH = FROM_LAT - SIZE_DEGREES;
    final double NORTH = FROM_LAT + SIZE_DEGREES;

    /**
     * Using a `ModifyStreets` modification, test that adjusting the `walkTimeFactor` appropriately changes route time.
     * Also indirectly tests that the necessary generalized cost data tables will be added to the network when missing.
     */
    @Test
    public void testGeneralizedCostWalk() {
        var network = FakeGraph.buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_LINES);

        var ms = new ModifyStreets();
        ms.allowedModes = EnumSet.of(StreetMode.WALK);
        ms.walkTimeFactor = 0.1;
        ms.polygons = new double[][][]{{
                {WEST, NORTH},
                {EAST, NORTH},
                {EAST, SOUTH},
                {WEST, SOUTH},
                {WEST, NORTH}
        }};

        var scenario = new Scenario();
        scenario.modifications = Arrays.asList(ms);

        var reachedVertices = getReachedVertices(network, new Scenario());
        var reachedVerticesWithModification = getReachedVertices(network, scenario);

        int nReachedVertices = reachedVertices.size();
        int nReachedVerticesWithModification = reachedVerticesWithModification.size();

        assertTrue(nReachedVertices > 500);
        assertTrue(nReachedVerticesWithModification > nReachedVertices * 2);

        int[] nLowerTimes = new int[1]; // Sidestep annoying Java "effectively final" rule.
        reachedVertices.forEachKey(v -> {
            int travelTime = reachedVertices.get(v);
            int travelTimeWithModification = reachedVerticesWithModification.get(v);
            assertTrue(travelTimeWithModification != -1);
            assertTrue(travelTimeWithModification <= travelTime);
            if (travelTimeWithModification != travelTime) {
                nLowerTimes[0] += 1;
                assertTrue(travelTimeWithModification * 1.3 < travelTime);
            }
            return true;
        });
        assertTrue(nLowerTimes[0] > 500);
    }

    TIntIntMap getReachedVertices(TransportNetwork network, Scenario scenario) {
        var modifiedNetwork = scenario.applyToTransportNetwork(network);
        Assertions.assertEquals(0, modifiedNetwork.scenarioApplicationWarnings.size());

        var extents = WebMercatorExtents.forWgsEnvelope(new Envelope(WEST, EAST, SOUTH, NORTH), WebMercatorGridPointSet.DEFAULT_ZOOM);
        var task = new TravelTimeSurfaceTask();
        task.accessModes = EnumSet.of(LegMode.WALK);
        task.directModes = EnumSet.of(LegMode.WALK);
        task.transitModes = EnumSet.noneOf(TransitModes.class);
        task.percentiles = new int[]{50};
        task.fromLat = FROM_LAT;
        task.fromLon = FROM_LON;
        task.north = extents.north;
        task.west = extents.west;
        task.height = extents.height;
        task.width = extents.width;
        task.zoom = extents.zoom;

        var streetRouter = new StreetRouter(modifiedNetwork.streetLayer);
        streetRouter.profileRequest = task;
        streetRouter.setOrigin(task.fromLat, task.fromLon);
        streetRouter.streetMode = StreetMode.WALK;
        streetRouter.timeLimitSeconds = TIME_LIMIT_SECONDS;
        streetRouter.route();

        return streetRouter.getReachedVertices();
    }
}