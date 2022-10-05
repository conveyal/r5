package com.conveyal.r5.streets;

import com.conveyal.modes.StreetMode;
import com.conveyal.r5.profile.ProfileRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimeDependentRoutingTest {

    @Test
    public void testProvideMyOwnTravelTime() {

        // Not time dependent yet
        StreetLayer streetLayer = new StreetLayer();
        int one = streetLayer.vertexStore.addVertex(0, 1);
        int two = streetLayer.vertexStore.addVertex(0, 2);
        int three = streetLayer.vertexStore.addVertex(0, 3);

        streetLayer.edgeStore.addStreetPair(one, two, 15000, 1);
        streetLayer.edgeStore.addStreetPair(two, three, 15000, 2);

        Edge e = streetLayer.getEdgeCursor(0);

        do {
            e.setFlag(EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeFlag.ALLOWS_CAR);
        } while (e.advance());

        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();

        StreetRouter streetRouter = new StreetRouter(streetLayer);
        streetRouter.setOrigin(one);
        streetRouter.route();
        RoutingState stateAtVertex = streetRouter.getStateAtVertex(three);

        assertEquals(24, stateAtVertex.durationSeconds);

        StreetRouter anotherStreetRouter = new StreetRouter(streetLayer);
        anotherStreetRouter.timeCalculator = new TraversalTimeCalculator() {
            @Override
            public int traversalTimeSeconds (Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
                return 30;
            }

            @Override
            public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
                return 0;
            }
        };
        anotherStreetRouter.setOrigin(one);
        anotherStreetRouter.route();
        RoutingState anotherStateAtVertex = anotherStreetRouter.getStateAtVertex(three);

        assertEquals(60, anotherStateAtVertex.durationSeconds);

    }

}
