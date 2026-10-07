package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.WALK_SPEED;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.routeFromVertex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopIndex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopVertex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests on connecting transit stops to the street network. Tests must continue to pass when
/// distinct car-to-stop connectors are introduced. Walk linking and walk routing results must not
/// change, and stops must not act as shortcuts between the streets around them via multiple links.
///
/// The scene is two parallel east-west streets connected only by a north-south street at their
/// east ends, with two stops in the gap between them. Both stops are nearer to South St, so both
/// walk links attach to South St. Any path between the two parallel streets must go the long way
/// around through East St. Scene illustrated in twoStreetScene.svg alongside source file.
public class StopLinkingTest {

    private static Scene scene;

    private static TransportNetwork network;

    /// The two-street scene shared by all tests in this class.
    static Scene twoStreetScene () {
        Scene scene = new Scene();
        SceneJunction stub = scene.junction("stub", 400, 100);
        SceneJunction northEast = scene.junction("ne", 800, 100);
        SceneJunction southEast = scene.junction("se", 800, 0);
        scene.way(WayPreset.STREET).named("North St").from(0, 100).via(stub).to(northEast);
        scene.way(WayPreset.STREET).named("South St").from(0, 0).to(southEast);
        scene.way(WayPreset.STREET).named("East St").from(northEast).to(southEast);
        scene.way(WayPreset.FOOTPATH).named("Stub Path").from(stub).north(50);
        scene.stop("mid", 400, 30);
        scene.stop("east", 700, 30);
        return scene;
    }

    @BeforeAll
    static void buildScene () {
        scene = twoStreetScene();
        network = scene.buildNetwork();
    }

    /// Each stop should be linked by a single edge pair to the nearest walkable street, here
    /// South St at 30 meters rather than North St at 70 meters. The link edges carry the LINK
    /// flag and permit walking. We deliberately do not assert the link's car permission because
    /// car-specific connectors will intentionally remove CAR permissions from link edges.
    @Test
    void walkLinkAttachesToNearestWalkableStreet () {
        int stopVertex = stopVertex(network, "mid");
        assertTrue(stopVertex >= 0, "The stop should be linked to the street network.");
        TIntList outgoing = network.streetLayer.outgoingEdges.get(stopVertex);
        assertEquals(1, outgoing.size(), "A stop vertex should have exactly one outgoing link edge.");
        EdgeStore.Edge link = network.streetLayer.edgeStore.getCursor(outgoing.get(0));
        assertTrue(link.getFlag(EdgeStore.EdgeFlag.LINK), "The stop's outgoing edge should be a link edge.");
        assertTrue(link.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN), "The link edge should be walkable.");
        assertEquals(30_000, link.getLengthMm(), 2_000,
            "The link should span the 30 meters from the stop to South St.");
        assertEquals(vertexAt(network, scene, 400, 0), link.getToVertex(),
            "The link should end at a vertex on South St directly south of the stop.");
    }

    /// Walk travel times from a street corner to the stops are checked with a tolerance to allow
    /// for per-edge rounding. These times result from stop link edges and should not change when
    /// car connectors are introduced.
    @Test
    void walkAccessTimesToStops () {
        StreetRouter walk = routeFromVertex(network, vertexAt(network, scene, 0, 0), StreetMode.WALK);
        TIntIntMap times = walk.getReachedStops();
        double expectedMid = (400 + 30) / WALK_SPEED;
        double expectedEast = (700 + 30) / WALK_SPEED;
        assertEquals(expectedMid, times.get(stopIndex(network, "mid")), expectedMid * 0.05,
            "Walk time to the mid stop should reflect 400 meters of street plus the 30 meter link.");
        assertEquals(expectedEast, times.get(stopIndex(network, "east")), expectedEast * 0.05,
            "Walk time to the east stop should reflect 700 meters of street plus the 30 meter link.");
    }

    /// The distance walked between the two stops should be the sum of both stops' links and the
    /// street between them. This routes from stop to stop minimizing distance, which is what
    /// TransferFinder does internally when building transfer tables. Stored transfer tables
    /// are empty here because only stops served by trip patterns are retained.
    @Test
    void stopToStopWalkDistanceMatchesStreetGeometry () {
        StreetRouter router = new StreetRouter(network.streetLayer);
        router.profileRequest = SceneRouting.defaultRequest();
        router.streetMode = StreetMode.WALK;
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        router.distanceLimitMeters = 2_000;
        router.setOrigin(stopVertex(network, "mid"));
        router.route();
        TIntIntMap distances = router.getReachedStops();
        assertEquals(360_000, distances.get(stopIndex(network, "east")), 5_000,
            "Stop to stop distance should be 30 + 300 + 30 meters over link, street and link.");
    }

    /// A stop between two streets must not act as a pedestrian shortcut between them.
    /// The walked distance from North St to the point on South St below the stop must reflect
    /// the 900 meter detour through East St, not the 130 meters through the stop vertex.
    @Test
    void noWalkShortcutThroughStopBetweenStreets () {
        assertNoShortcut(StreetMode.WALK);
    }

    /// Link edges at a stop must not let cars pass through the stop vertex from one street to the other.
    @Test
    void noCarShortcutThroughStopBetweenStreets () {
        assertNoShortcut(StreetMode.CAR);
    }

    /// Route in the given mode between the North St junction above the stop and the South St
    /// vertex below it, in both directions, asserting the traveled distance is the long way around.
    private void assertNoShortcut (StreetMode mode) {
        int north = vertexAt(network, scene, 400, 100);
        int south = vertexAt(network, scene, 400, 0);
        assertEquals(900_000, distance(north, south, mode), 20_000,
            "Travel from North St to South St should detour 400 + 100 + 400 meters via East St.");
        assertEquals(900_000, distance(south, north, mode), 20_000,
            "Travel from South St to North St should take the same detour.");
    }

    /// @return the distance in millimeters traveled on the fastest path between two street vertices.
    private int distance (int fromVertex, int toVertex, StreetMode mode) {
        StreetRouter router = routeFromVertex(network, fromVertex, mode);
        StreetRouter.State state = router.getStateAtVertex(toVertex);
        assertTrue(state != null, "The destination vertex should be reachable.");
        return state.getRoutingVariable(StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS);
    }

}
