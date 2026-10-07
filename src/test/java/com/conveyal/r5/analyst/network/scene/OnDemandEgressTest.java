package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.analyst.OnDemandAccess;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.NOON;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of the walk search that follows on-demand rides, particularly its per-leg walking limit.
/// The limit constrains a quantity the search does not minimize, which makes it approximate in the
/// absence of Pareto-optimal routing. No over-limit walk is ever reported, but competition between
/// on-demand alighting points can hide the true best path (see StreetRouter.legTimeLimitSeconds).
/// One test documents that known approximation so a change in behavior will be noticed.
public class OnDemandEgressTest {

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    /// Two alighting points whose following walks will converge and share edges, with car times
    /// decoupled from walk distances. Direct Rd reaches S2 quickly; Loop Rd reaches S1 only by
    /// a 3800 meter detour, so the ride to S1 arrives several minutes after the ride to S2 (the
    /// service's duration factor widens the gap). Walking paths: 156 meters S1 to W, 556 meters
    /// S2 to W, then shared edges W to V (78 m) and V to D (200 m). The drop-off zone contains
    /// S1 and S2 only. The service is available all day so only geometry determines times.
    static TransportNetwork convergingEgressNetwork (Scene scene) {
        SceneJunction o = scene.junction("origin", 0, 500);
        SceneJunction s1 = scene.junction("s1", 1000, 100);
        SceneJunction s2 = scene.junction("s2", 1000, 500);
        SceneJunction w = scene.junction("w", 1156, 100);
        SceneJunction v = scene.junction("v", 1234, 100);
        scene.way(WayPreset.STREET).named("Direct Rd").from(o).to(s2);
        scene.way(WayPreset.STREET).named("Loop Rd").from(o)
            .south(500).west(800).south(300).east(1800).to(s1);
        scene.way(WayPreset.FOOTPATH).named("S1 Path").from(s1).to(w);
        scene.way(WayPreset.FOOTPATH).named("S2 Path").from(s2).east(156).to(w);
        scene.way(WayPreset.FOOTPATH).named("Tail Path").from(w).to(v);
        scene.way(WayPreset.FOOTPATH).named("Dead End Path").from(v).east(200);
        ScenePolygon pickup = scene.rectPolygon("pickup", -100, 450, 100, 550);
        ScenePolygon dropOff = scene.rectPolygon("dropoff", 950, 50, 1050, 550);
        scene.onDemand("converging")
            .fromPolygon(pickup).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(dropOff).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationFactor(3.0);
        return scene.buildNetwork();
    }

    /// Run an on-demand search on the converging scene with the given per-leg walk limit in minutes.
    private static OnDemandAccess routeWithWalkLimit (TransportNetwork network, Scene scene, int maxWalkMinutes) {
        StreetRouter walk = SceneRouting.route(network, scene, 20, 500, StreetMode.WALK);
        // The pipeline reads its egress walking limit from the access router's request.
        walk.profileRequest.maxWalkTime = maxWalkMinutes;
        return OnDemandAccess.route(walk, List.of(onDemand(network, "converging")), NOON, null);
    }

    /// With a 10 minute walking limit, D is reported unreachable even though a compliant path
    /// exists: alight at S1 and walk 434 meters (about 5.5 minutes). The S2 state arrives with a
    /// lower total, so the S1 walk is discarded as dominated on the shared edge W-V, and the
    /// surviving S2 walk hits the limit before D. This documents the accepted approximation of
    /// the generation-time leg limit; an exact (total, leg) Pareto search would report D
    /// reachable via S1. The test first verifies the premises that make the test work.
    @Test
    void compliantPathHiddenByDominance () {
        Scene scene = new Scene();
        TransportNetwork network = convergingEgressNetwork(scene);
        OnDemandAccess flex = routeWithWalkLimit(network, scene, 10);
        int atS1 = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1000, 100));
        int atS2 = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1000, 500));
        assertTrue(atS1 - atS2 > 308,
            "The ride to S1 must arrive enough later that the S2 walk dominates the shared edge, "
                + "but the gap was only " + (atS1 - atS2) + " seconds.");
        assertNotEquals(Integer.MAX_VALUE, flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1234, 100)),
            "Both egress walks fit within the limit as far as V.");
        assertEquals(Integer.MAX_VALUE, flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1434, 100)),
            "D should be reported unreachable: the dominant S2 walk exceeds the limit before D, "
                + "and the compliant S1 walk was discarded on the shared edge.");
    }

    /// With the walking limit raised to 12 minutes, the dominant walk from S2 fits and D is
    /// reached, confirming that only the per-leg limit blocked it in the 10 minute case.
    @Test
    void raisedWalkLimitReachesDeadEnd () {
        Scene scene = new Scene();
        TransportNetwork network = convergingEgressNetwork(scene);
        OnDemandAccess flex = routeWithWalkLimit(network, scene, 12);
        int atS2 = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1000, 500));
        int atD = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 1434, 100));
        assertNotEquals(Integer.MAX_VALUE, atD, "With a longer walking limit D should be reached.");
        assertEquals((556 + 78 + 200) / SceneRouting.WALK_SPEED, atD - atS2, 30,
            "D should be reached by the dominant walk from S2.");
    }

}
