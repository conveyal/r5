package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.routeWithOnDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of on-demand services whose pick-up and drop-off places are polygonal zones. When a
/// search origin lies within the pick-up zone, the vehicle will come to the rider's location
/// along the street. A start state is injected at that location. Otherwise the rider would
/// have to walk to a vertex at the end of an edge to be picked up. When the origin lies outside
/// the zone, boarding happens at zone vertices reached by the rider's own walk search.
///
/// All tests share one scene, twoVillagesNetwork: Long Rd runs between two villages, each a
/// square of small streets — village A on the west, village B on the east. Several services
/// are layered on this network, differing in their zones and delays to serve different tests.
///
/// The "village" service's pick-up zone covers the eastern half of village A plus
/// a western chunk of Long Rd, so it contains ordinary boarding vertices. The "mid-edge"
/// service's pick-up zone covers only a mid-edge stretch of Long Rd and contains no vertices
/// at all, so its trips can begin only through an injected start state. The "delayed" service is
/// identical to mid-edge, but introduces a pick-up delay. All services share the same drop-off
/// zone covering the western half of village B plus an eastern chunk of Long Rd.
///
/// All tests measure times at vertices. The shared drop-off zone deliberately contains
/// vertices, sidestepping the known weakness that on-demand arrival can only happen from
/// vertices. A drop-off zone containing no vertices (like the "mid-edge" service's pick-up zone)
/// is unreachable until and unless intra-edge alighting is implemented.
///
/// The scene is illustrated in twoVillagesNetwork.svg alongside this source file.
public class OnDemandZoneAccessTest {

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    /// Long Rd runs 2000 meters between two villages, each a 400 meter square of streets with
    /// a north-south cross street through its middle. The three services layered on the
    /// network are described in the class comment.
    static TransportNetwork twoVillagesNetwork (Scene scene) {
        Village a = addVillage(scene, "A", 0);
        Village b = addVillage(scene, "B", 2400);
        scene.way(WayPreset.STREET).named("Long Rd").from(a.se).to(b.sw);
        ScenePolygon villagePickup = scene.rectPolygon("village-pickup", 150, -50, 1200, 450);
        ScenePolygon midEdgePickup = scene.rectPolygon("mid-edge-pickup", 800, -50, 1200, 50);
        ScenePolygon dropOff = scene.rectPolygon("dropoff", 1600, -50, 2650, 450);
        scene.onDemand("village")
            .fromPolygon(villagePickup).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(dropOff).dropOffWindow(WINDOW_START, WINDOW_END);
        scene.onDemand("mid-edge")
            .fromPolygon(midEdgePickup).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(dropOff).dropOffWindow(WINDOW_START, WINDOW_END);
        scene.onDemand("delayed")
            .fromPolygon(midEdgePickup).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(dropOff).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(300);
        return scene.buildNetwork();
    }

    /// The south corner junctions of a village, where Long Rd can attach.
    private record Village (SceneJunction sw, SceneJunction se) { }

    /// Add a 400 meter square of streets with its southwest corner at the given x coordinate
    /// on Long Rd's line, divided by a north-south cross street through its middle. The
    /// corners and cross street ends become junction vertices.
    private static Village addVillage (Scene scene, String name, int x) {
        SceneJunction sw = scene.junction(name + "-sw", x, 0);
        SceneJunction s = scene.junction(name + "-s", x + 200, 0);
        SceneJunction se = scene.junction(name + "-se", x + 400, 0);
        SceneJunction nw = scene.junction(name + "-nw", x, 400);
        SceneJunction n = scene.junction(name + "-n", x + 200, 400);
        SceneJunction ne = scene.junction(name + "-ne", x + 400, 400);
        scene.way(WayPreset.STREET).named(name + " South St").from(sw).via(s).to(se);
        scene.way(WayPreset.STREET).named(name + " North St").from(nw).via(n).to(ne);
        scene.way(WayPreset.STREET).named(name + " West St").from(sw).to(nw);
        scene.way(WayPreset.STREET).named(name + " East St").from(se).to(ne);
        scene.way(WayPreset.STREET).named(name + " Cross St").from(s).to(n);
        return new Village(sw, se);
    }

    /// A rider standing mid-edge on Long Rd inside the pick-up zone, 600 meters from the
    /// nearest in-zone vertex. Boarding there through the injected start state should clearly
    /// beat walking back to the village (over 450 seconds) before riding.
    @Test
    void midEdgeBetweenVillages () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 1000, 30, onDemand(network, "village"));
        int timeToDropOff = router.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        assertTrue(timeToDropOff < 300,
            "Boarding at the origin and riding 1400 meters should beat walking to any in-zone "
                + "vertex, but the drop-off zone was reached in " + timeToDropOff + " seconds.");
        assertTrue(timeToDropOff > 30,
            "The off-street gap and the 1400 meter ride still take real time, but the "
                + "drop-off zone was reached in only " + timeToDropOff + " seconds.");
    }

    /// A rider outside the pick-up zone, on the western edge of village A. No start
    /// state is injected at their origin; they walk about 420 meters into the village to the
    /// cross street junctions inside the zone and board there, on their own clock. The lower
    /// bound excludes boarding anywhere outside the zone, which would reach the drop-off zone
    /// in about 210 seconds; the upper bound excludes walking the whole way (about 2000
    /// seconds).
    @Test
    void walkIntoPickupZone () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 20, 200, onDemand(network, "village"));
        int timeToDropOff = router.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        assertTrue(timeToDropOff >= 350 && timeToDropOff <= 800,
            "The trip should include a 420 meter walk into the zone before riding, but the "
                + "drop-off zone was reached in " + timeToDropOff + " seconds.");
    }

    /// A rider among the short streets of village A, inside the pick-up zone. The
    /// vehicle meets them on the adjacent street drives them out to Long Rd.
    /// Walking the whole way would take about 1800 seconds.
    @Test
    void shortStreetOrigin () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 260, 350, onDemand(network, "village"));
        int timeToDropOff = router.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        assertTrue(timeToDropOff > 30 && timeToDropOff < 400,
            "The ride out through the village should be priced as riding from the rider's own "
                + "street, but the drop-off zone was reached in " + timeToDropOff + " seconds.");
    }

    /// An on-demand ride ending among the short streets of village B. The ride is clipped to the
    /// drop-off zone's vertices inside the village, and walking continues beyond the zone.
    /// The village's east side lies outside the zone, so it should be reached the length of
    /// one block's walk after the cross street.
    @Test
    void dropOffInVillage () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 1000, 30, onDemand(network, "village"));
        int timeToCrossSt = router.getTravelTimeToVertex(vertexAt(network, scene, 2600, 0));
        int timeToEastSide = router.getTravelTimeToVertex(vertexAt(network, scene, 2800, 0));
        assertTrue(timeToCrossSt < 300,
            "The ride should continue into the village streets inside the drop-off zone, but "
                + "the cross street was reached in " + timeToCrossSt + " seconds.");
        assertEquals(200 / SceneRouting.WALK_SPEED, timeToEastSide - timeToCrossSt, 15,
            "The village's east side is outside the drop-off zone and should be reached by "
                + "walking one 200 meter block from the cross street.");
    }

    /// A rider waiting mid-edge inside the "mid-edge" pick-up zone, which contains no
    /// vertices, so the injected start state is the only possible boarding. Without injection
    /// they would first walk about 600 meters to a junction (over 400 seconds) before any
    /// boarding state existed, when in reality the vehicle meets them mid-edge.
    @Test
    void midEdgeOrigin () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 1000, 30, onDemand(network, "mid-edge"));
        int timeToDropOff = router.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        assertTrue(timeToDropOff < 300,
            "Boarding at the origin and riding 1400 meters should beat any walk to a junction, "
                + "but the drop-off zone was reached in " + timeToDropOff + " seconds.");
        assertTrue(timeToDropOff > 30,
            "The off-street gap and the 1400 meter ride still take real time, but the "
                + "drop-off zone was reached in only " + timeToDropOff + " seconds.");
    }

    /// All on-demand boarding details should apply to the injected start point before any ride.
    /// On a service with a five minute pick-up delay, a trip beginning mid-edge takes exactly
    /// five minutes longer than on the otherwise identical service without the delay.
    @Test
    void pickupDelay () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        int dropOffVertex = vertexAt(network, scene, 2400, 0);
        StreetRouter plain = routeWithOnDemand(network, scene, 1000, 30, onDemand(network, "mid-edge"));
        StreetRouter delayed = routeWithOnDemand(network, scene, 1000, 30, onDemand(network, "delayed"));
        int plainSeconds = plain.getTravelTimeToVertex(dropOffVertex);
        int delayedSeconds = delayed.getTravelTimeToVertex(dropOffVertex);
        assertEquals(300, delayedSeconds - plainSeconds, 2,
            "A five minute pick-up delay should be the only difference between the two rides.");
    }

    /// An origin point along the same long edge but outside the "mid-edge" pick-up zone should
    /// not be injected as a start point for that service. As no vertex is inside the pick-up zone
    /// either, routing finds the service unusable and reaches the drop-off zone only by
    /// walking the whole way. Note that this checks current rather than intended behavior: in
    /// reality the service remains usable by walking 200 meters along the road to the zone
    /// boundary and boarding mid-edge there. If and when initiating routing at zone boundaries
    /// is implemented, this test should instead expect a walk to the boundary followed by a ride.
    @Test
    void originOutsidePickupZone () {
        Scene scene = new Scene();
        TransportNetwork network = twoVillagesNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 600, 30, onDemand(network, "mid-edge"));
        int timeToDropOff = router.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        double pureWalkSeconds = (30 + 1800) / SceneRouting.WALK_SPEED;
        assertEquals(pureWalkSeconds, timeToDropOff, pureWalkSeconds * 0.1,
            "With no boarding state anywhere, the drop-off zone should be reached only on foot.");
    }

}

