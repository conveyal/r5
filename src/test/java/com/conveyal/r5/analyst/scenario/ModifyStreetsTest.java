package com.conveyal.r5.analyst.scenario;

import com.beust.jcommander.internal.Lists;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.EnumSet;

/**
 * Test that modifying streets works correctly.
 */
public class ModifyStreetsTest {
    double fromLat = 40.0;
    double fromLon = -83.0;
    int timeLimitSeconds = 1200;
    // Analysis bounds
    double SIZE = 0.05;
    double WEST = fromLon - SIZE;
    double EAST = fromLon + SIZE;
    double SOUTH = fromLat - SIZE;
    double NORTH = fromLat + SIZE;

    /**
     * Using a `ModifyStreets` modification, test that adjusting the `walkTimeFactor` appropriately changes route time.
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
        scenario.modifications = Lists.newArrayList(ms);

        var reachedVertexes = getReachedVertexes(network, new Scenario());
        var reachedVertexesWithModification = getReachedVertexes(network, scenario);

        int totalGreater = 0;
        for (int i = 0; i < reachedVertexes.size(); i++) {
            int baselineTime = reachedVertexes.get(i);
            int modTime = reachedVertexesWithModification.get(i);
            Assertions.assertTrue(baselineTime >= modTime);
            if (baselineTime > modTime) {
                totalGreater++;
            }
        }
        Assertions.assertEquals(454, totalGreater);
    }

    TIntIntMap getReachedVertexes (TransportNetwork network, Scenario scenario) {
        var modifiedNetwork = scenario.applyToTransportNetwork(network);
        Assertions.assertEquals(0, modifiedNetwork.scenarioApplicationWarnings.size());

        var extents = WebMercatorExtents.forWgsEnvelope(new Envelope(WEST, EAST, SOUTH, NORTH), WebMercatorGridPointSet.DEFAULT_ZOOM);
        var task = new TravelTimeSurfaceTask();
        task.accessModes = EnumSet.of(LegMode.WALK);
        task.directModes = EnumSet.of(LegMode.WALK);
        task.transitModes = EnumSet.noneOf(TransitModes.class);
        task.percentiles = new int[]{50};
        task.fromLat = fromLat;
        task.fromLon = fromLon;
        task.north = extents.north;
        task.west = extents.west;
        task.height = extents.height;
        task.width = extents.width;
        task.zoom = extents.zoom;

        var streetRouter = new StreetRouter(modifiedNetwork.streetLayer);
        streetRouter.profileRequest = task;
        streetRouter.setOrigin(task.fromLat, task.fromLon);
        streetRouter.streetMode = StreetMode.WALK;
        streetRouter.timeLimitSeconds = timeLimitSeconds;
        streetRouter.route();

        return streetRouter.getReachedVertices();
    }
}