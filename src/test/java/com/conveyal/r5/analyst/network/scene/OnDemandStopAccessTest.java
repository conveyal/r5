package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.flex.MeetingAreas;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.WALK_SPEED;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.routeWithOnDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopIndex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopVertex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of on-demand (flex) service at transit stops, covering combinations of flex vehicle and
/// walking to access those stops. A stop is walk-linked to the nearest walkable street, which need
/// not permit cars. A service that picks people up or drops them off at stops acts through the
/// stops' [MeetingAreas]. The vehicle meets the rider anywhere in the walkable area around the stop
/// that touches drivable streets, so it may pick up or drop off on a different street than the one
/// the stop is linked to. This is necessary to accommodate on-demand services that pick people up
/// at a larger stop complex of some kind, not physically right at the stop ID given in the feed.
public class OnDemandStopAccessTest {

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    /// One street with one stop beside it, a side street branching off that main street, and a
    /// drop-off polygon at the main street's east end. The side street junction lies 250 meters
    /// along the street from the stop's link point, between the smaller and larger meeting area
    /// discovery budgets, so tests can observe which budget was applied. The side street is placed
    /// so that it is always farther from the stop than Main St, ensuring the stop is linked to
    /// Main Street.
    static TransportNetwork drivableStreetNetwork (Scene scene, int stopDistance) {
        SceneJunction side = scene.junction("side", 750, 0);
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).via(side).east(250);
        scene.way(WayPreset.STREET).named("Side St").from(side).north(200);
        SceneStop stop = scene.stop("curb", 500, stopDistance);
        ScenePolygon zone = scene.rectPolygon("zone", 900, -50, 1100, 50);
        scene.onDemand("flexOut")
            .fromStops(stop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zone).dropOffWindow(WINDOW_START, WINDOW_END);
        scene.onDemand("flexIn")
            .fromPolygon(zone).pickupWindow(WINDOW_START, WINDOW_END)
            .toStops(stop).dropOffWindow(WINDOW_START, WINDOW_END);
        return scene.buildNetwork();
    }

    /// A stop right beside a car-permitting street has a meeting area that starts at the curb,
    /// so on-demand pick-up works directly. Walking from the origin to the zone would take over
    /// 400 seconds, so a time under 300 proves the transit option was used.
    @Test
    void onDemandPickupOnDrivableStreet () {
        Scene scene = new Scene();
        TransportNetwork network = drivableStreetNetwork(scene, 10);
        var router = routeWithOnDemand(network, scene, 450, 0, onDemand(network, "flexOut"));
        double expectedToStop = (50 + 10) / WALK_SPEED;
        assertEquals(expectedToStop, router.getReachedStops().get(stopIndex(network, "curb")), 5,
            "The stop should be reached by walking 50 meters of street and the 10 meter link.");
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        assertTrue(timeToZone < 300,
            "On-demand service from the stop should reach the zone far faster than walking, but took "
                + timeToZone + " seconds.");
    }

    /// The rider should never transit the stop itself. Boarding happens wherever the rider's own
    /// walk first reaches that stop's meeting area, so a stop placed 200 meters from the street
    /// imposes no detour via the stop on a rider already standing at the curb. The walk link
    /// is unchanged, so the stop itself is still reached in 200 meters of walking at the right pace.
    @Test
    void pickupAtSetBackStop () {
        Scene scene = new Scene();
        TransportNetwork network = drivableStreetNetwork(scene, 200);
        var router = routeWithOnDemand(network, scene, 500, 0, onDemand(network, "flexOut"));
        int timeToStop = router.getReachedStops().get(stopIndex(network, "curb"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        double linkWalkSeconds = 200 / WALK_SPEED;
        assertEquals(linkWalkSeconds, timeToStop, linkWalkSeconds * 0.1,
            "Reaching the stop itself should still be the 200 meter link walk at walking pace.");
        assertTrue(timeToZone < 100,
            "A rider at the curb should board there and ride straight out, but the zone took "
                + timeToZone + " seconds.");
        assertTrue(timeToZone < timeToStop,
            "The ride out should not detour through the stop point at all.");
    }

    /// A stop on a pedestrian plaza that connects to a drivable street only on foot.
    /// The scene is illustrated in plazaNetwork.svg alongside this source file.
    static TransportNetwork plazaNetwork (Scene scene) {
        SceneJunction jMain = scene.junction("main", 400, 0);
        SceneJunction jPlaza = scene.junction("plaza", 400, 50);
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).via(jMain).east(600);
        scene.way(WayPreset.FOOTPATH).named("Plaza Path").from(jMain).to(jPlaza);
        scene.way(WayPreset.PEDESTRIAN).named("Plaza").from(jPlaza).east(200);
        SceneStop stop = scene.stop("plaza-stop", 500, 60);
        ScenePolygon zone = scene.rectPolygon("zone", 900, -50, 1100, 50);
        scene.onDemand("toStop")
            .fromPolygon(zone).pickupWindow(WINDOW_START, WINDOW_END)
            .toStops(stop).dropOffWindow(WINDOW_START, WINDOW_END);
        scene.onDemand("fromStop")
            .fromStops(stop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zone).dropOffWindow(WINDOW_START, WINDOW_END);
        return scene.buildNetwork();
    }

    /// An on-demand ride to a stop on a car-free plaza drops the passenger off on the nearest
    /// drivable street, and the rider walks the rest of the way to the stop and onward into the
    /// plaza. Walking the whole way from the origin would take about 550 seconds to the stop and
    /// 615 to the far plaza end, so times under 400 prove the on-demand service was used.
    @Test
    void dropOffOnCarfreePlaza () {
        Scene scene = new Scene();
        TransportNetwork network = plazaNetwork(scene);
        var router = routeWithOnDemand(network, scene, 950, 0, onDemand(network, "toStop"));
        int timeToStop = router.getReachedStops().get(stopIndex(network, "plaza-stop"));
        assertTrue(timeToStop > 0 && timeToStop < 400,
            "The ride and a 160 meter walk should reach the stop well before a pure walk, but took "
                + timeToStop + " seconds.");
        int timeToPlazaEnd = router.getTravelTimeToVertex(vertexAt(network, scene, 600, 50));
        assertTrue(timeToPlazaEnd < 400,
            "Walking should continue past the drop-off into the plaza, but its far end took "
                + timeToPlazaEnd + " seconds.");
    }

    /// On-demand pickup for a rider on a car-free plaza. The vehicle cannot enter the plaza,
    /// so it meets the rider at the nearest point of the stop's meeting area. Here the origin
    /// itself is accepted by the boarding predicate (the street below the plaza is in the boarding
    /// area), so origin is injected. The gap from the rider to the street is priced at walking
    /// pace, the same treatment every car search gives its origin point.
    @Test
    void pickUpOnCarfreePlaza () {
        Scene scene = new Scene();
        TransportNetwork network = plazaNetwork(scene);
        var router = routeWithOnDemand(network, scene, 550, 50, onDemand(network, "fromStop"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        assertTrue(timeToZone >= 50 && timeToZone <= 450,
            "Riding on-demand should include the priced gap to the street and the drive to the zone, "
                + "but took " + timeToZone + " seconds.");
    }

    /// A stop on a walking island with no drivable street anywhere near it, plus a distant
    /// street network holding the polygon end of both flex services.
    /// The scene is illustrated in islandNetwork.svg alongside this source file.
    static TransportNetwork islandNetwork (Scene scene) {
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).east(1000);
        scene.way(WayPreset.FOOTPATH).named("Island Path").from(1100, 1100).east(200);
        SceneStop stop = scene.stop("island", 1200, 1110);
        ScenePolygon zone = scene.rectPolygon("zone", 900, -50, 1100, 50);
        scene.onDemand("toIsland")
            .fromPolygon(zone).pickupWindow(WINDOW_START, WINDOW_END)
            .toStops(stop).dropOffWindow(WINDOW_START, WINDOW_END);
        scene.onDemand("fromIsland")
            .fromStops(stop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zone).dropOffWindow(WINDOW_START, WINDOW_END);
        return scene.buildNetwork();
    }

    /// When no drivable street is within reach of a stop, taking an on-demand service to it is
    /// correctly seen as impossible. But the build should log a warning for the stop.
    @Test
    void noOnDemandToIsolatedStop () {
        Scene scene = new Scene();
        TransportNetwork network = islandNetwork(scene);
        assertTrue(stopVertex(network, "island") >= 0,
            "The stop should still be walk-linked to its island.");
        var router = routeWithOnDemand(network, scene, 950, 0, onDemand(network, "toIsland"));
        assertFalse(router.getReachedStops().containsKey(stopIndex(network, "island")),
            "No ride should deliver riders to a stop with no drivable street in reach.");
        assertEquals(Integer.MAX_VALUE, router.getTravelTimeToVertex(vertexAt(network, scene, 1200, 1100)),
            "The island around the stop should be unreachable.");
    }

    /// Taking on-demand service out of the island stop is also impossible,
    /// but walking to the stop over its walk link is still possible.
    @Test
    void noOnDemandFromIsolatedStop () {
        Scene scene = new Scene();
        TransportNetwork network = islandNetwork(scene);
        var router = routeWithOnDemand(network, scene, 1150, 1100, onDemand(network, "fromIsland"));
        double expectedToStop = (50 + 10) / WALK_SPEED;
        assertEquals(expectedToStop, router.getReachedStops().get(stopIndex(network, "island")), 5,
            "The walk link should still allow riders to reach the stop.");
        assertEquals(Integer.MAX_VALUE, router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0)),
            "On-demand should not pick riders up at a stop with no drivable street in reach.");
    }

    /// A tricky situation for car versus walk links to transit stops: an elevated station directly
    /// above a motorway, with a frontage road right beside it across a barrier. Access to the
    /// station is via a pedestrian plaza and footpath leading west to Access Road. R5 considers the
    /// motorway un-linkable, while the frontage road is drivable, walkable, and linkable but has no
    /// direct pedestrian connection to the station. Exploration of the walking network is necessary
    /// to correctly identify Access Road as the correct car drop-off point. The two undeclared
    /// geometric intersections are intentional grade separated crossings. The scene is illustrated
    /// in stationNetwork.svg alongside this source file.
    static TransportNetwork stationNetwork (Scene scene) {
        SceneJunction jAccessMain = scene.junction("access-main", 200, 0);
        SceneJunction jFrontMain = scene.junction("front-main", 1000, 0);
        SceneJunction jAccessPath = scene.junction("access-path", 200, 110);
        SceneJunction jPlazaPath = scene.junction("plaza-path", 400, 110);
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).via(jAccessMain).via(jFrontMain).east(1000);
        scene.way(WayPreset.STREET).named("Access Rd").from(200, 300).via(jAccessPath).to(jAccessMain);
        scene.way(WayPreset.FOOTPATH).named("Station Approach").from(jAccessPath).to(jPlazaPath);
        scene.way(WayPreset.PEDESTRIAN).named("Plaza").from(jPlazaPath).east(200);
        scene.way(WayPreset.SERVICE).named("Frontage Rd").from(0, 80).east(1000).to(jFrontMain);
        scene.way(WayPreset.MOTORWAY).named("Motorway").from(0, 100).east(2000);
        SceneStop stop = scene.stop("elevated", 500, 100);
        ScenePolygon zone = scene.rectPolygon("zone", 1900, -50, 2100, 50);
        scene.onDemand("taxiOut")
            .fromStops(stop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zone).dropOffWindow(WINDOW_START, WINDOW_END);
        return scene.buildNetwork();
    }

    /// The premise of the station scene, which must continue to hold when specialized car
    /// connectors are implemented. The station's walk link must attach to the plaza 10 meters away,
    /// not to the motorway directly beneath it and not to the walkable frontage road 20 meters away.
    @Test
    void stationWalkLinkAttachesToPlaza () {
        Scene scene = new Scene();
        TransportNetwork network = stationNetwork(scene);
        assertEquals(2, scene.findUndeclaredCrossings().size(),
            "The two grade separations should be the only undeclared crossings.");
        int stopVertex = stopVertex(network, "elevated");
        TIntList outgoing = network.streetLayer.outgoingEdges.get(stopVertex);
        assertEquals(1, outgoing.size(), "The stop should have a single outgoing link edge.");
        EdgeStore.Edge link = network.streetLayer.edgeStore.getCursor(outgoing.get(0));
        assertEquals(10_000, link.getLengthMm(), 2_000,
            "The walk link should span the 10 meters to the plaza.");
        assertEquals(vertexAt(network, scene, 500, 110), link.getToVertex(),
            "The walk link should attach to the plaza, not the motorway or the frontage road.");
    }

    /// Riding on-demand services out of a stop complex must board at the best street a pedestrian
    /// can actually walk to, which is Access Road, 310 meters away through the plaza and footpath.
    /// The cost of that walk is determined by the rider's own access search at walking pace. The
    /// frontage road 20 meters away across a barrier is not in the stop's meeting area, which
    /// blocks injection of the origin point. The nearest drivable edge to the rider is the frontage
    /// road, and its endpoints fail the boarding predicate. The lower bound excludes the frontage
    /// road shortcut, which would reach the zone in about 200 seconds. The upper bound excludes
    /// walking all the way, which takes about 1740 seconds.
    @Test
    void riderWalksFromCarToStation () {
        Scene scene = new Scene();
        TransportNetwork network = stationNetwork(scene);
        var router = routeWithOnDemand(network, scene, 550, 110, onDemand(network, "taxiOut"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 2000, 0));
        assertTrue(timeToZone >= 380 && timeToZone <= 800,
            "The on-demand ride should start at Access Rd after a 310 meter priced walk and drive "
                + "around via Main St, but the zone was reached in " + timeToZone + " seconds.");
    }

}
