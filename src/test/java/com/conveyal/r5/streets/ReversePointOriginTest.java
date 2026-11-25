package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reverse search from a point between two vertices must give each vertex the cost of its own part of the split
 * edge, and paths reconstructed from it must keep that cost.
 */
public class ReversePointOriginTest {
    private static final double LAT = 1.0001;
    private static final double LON = 0.0025; // a quarter of the way from A to B

    private StreetLayer streetLayer;
    private TransportNetwork transportNetwork;
    private int A, B, C;

    @BeforeEach
    public void setUp() {
        streetLayer = new StreetLayer();
        transportNetwork = new TransportNetwork();
        transportNetwork.streetLayer = streetLayer;

        A = streetLayer.vertexStore.addVertex(1, 0);
        B = streetLayer.vertexStore.addVertex(1, 0.01);
        C = streetLayer.vertexStore.addVertex(1, 0.02);
        streetLayer.edgeStore.addStreetPair(A, B, 1_113_000, -1);
        streetLayer.edgeStore.addStreetPair(B, C, 1_113_000, -1);
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);
        do {
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeStore.EdgeFlag.LINKABLE);
        } while (e.advance());
        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();
    }

    private StreetRouter reverseRouter() {
        StreetRouter router = new StreetRouter(streetLayer);
        router.profileRequest = new ProfileRequest();
        router.profileRequest.reverseSearch = true;
        router.streetMode = StreetMode.WALK;
        assertTrue(router.setOrigin(LAT, LON));
        router.route();
        return router;
    }

    @Test
    public void verticesGetTheCostOfTheirOwnPartOfTheEdge() {
        Split split = streetLayer.findSplit(LAT, LON, StreetLayer.LINK_RADIUS_METERS, StreetMode.WALK);
        assertNotNull(split);
        assertEquals(A, split.vertex0);

        StreetRouter router = reverseRouter();
        StreetRouter.State atA = router.getStateAtVertex(A);
        StreetRouter.State atB = router.getStateAtVertex(B);
        assertEquals(split.distance0_mm + split.distanceToEdge_mm, atA.distance);
        assertEquals(split.distance1_mm + split.distanceToEdge_mm, atB.distance);
        assertTrue(atA.getDurationSeconds() < atB.getDurationSeconds());
    }

    @Test
    public void reversedPathKeepsTheCostOfTheOrigin() {
        StreetRouter router = reverseRouter();
        for (int vertex : new int[] {A, B, C}) {
            StreetRouter.State state = router.getStateAtVertex(vertex);
            StreetPath path = new StreetPath(state, transportNetwork, true);
            assertEquals(state.getDurationSeconds(), path.getDuration());
            assertEquals(state.distance, path.getDistance());
        }
    }
}
