package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
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

        StreetRouter anotherStreetRouter = new StreetRouter(streetLayer, (edge, durationSeconds, streetMode, req) -> 30);
        anotherStreetRouter.setOrigin(one);
        anotherStreetRouter.route();
        StreetRouter.State anotherStateAtVertex = anotherStreetRouter.getStateAtVertex(three);

        assertEquals(60, anotherStateAtVertex.durationSeconds);

        // Time dependent. This should evaluate (t_n = t_n-1 + (t_n-1 + 40), t_0 = 0) at n=2
        StreetRouter yetAnotherStreetRouter = new StreetRouter(streetLayer, (edge, durationSeconds, streetMode, req) -> durationSeconds + 40);
        yetAnotherStreetRouter.setOrigin(one);
        yetAnotherStreetRouter.route();
        StreetRouter.State yetAnotherStateAtVertex = yetAnotherStreetRouter.getStateAtVertex(three);

        assertEquals(120, yetAnotherStateAtVertex.durationSeconds);


    }

}
