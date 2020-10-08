package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TimeDependentRoutingTest {

    @Test
    public void testProvideMyOwnTravelTime() {

        // Not time dependent yet
        StreetLayer streetLayer = new StreetLayer(new TNBuilderConfig());
        int one = streetLayer.vertexStore.addVertex(0, 1);
        int two = streetLayer.vertexStore.addVertex(0, 2);
        int three = streetLayer.vertexStore.addVertex(0, 3);

        streetLayer.edgeStore.addStreetPair(one, two, 15000, 1);
        streetLayer.edgeStore.addStreetPair(two, three, 15000, 2);

        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);

        do {
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        } while (e.advance());

        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();

        StreetRouter streetRouter = new StreetRouter(streetLayer);
        streetRouter.setOrigin(one);
        streetRouter.route();
        StreetRouter.State stateAtVertex = streetRouter.getStateAtVertex(three);

        assertEquals(24, stateAtVertex.durationSeconds);

        StreetRouter anotherStreetRouter = new StreetRouter(streetLayer);
        anotherStreetRouter.timeCalculator = new TraversalTimeCalculator() {
            @Override
            public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
                return 30;
            }

            @Override
            public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
                return 0;
            }
        };
        anotherStreetRouter.setOrigin(one);
        anotherStreetRouter.route();
        StreetRouter.State anotherStateAtVertex = anotherStreetRouter.getStateAtVertex(three);

        assertEquals(60, anotherStateAtVertex.durationSeconds);

    }

}
