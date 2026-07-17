package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import org.junit.jupiter.api.Disabled;
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

/// Tests of on-demand (flex) service at transit stops, covering combinations of driving and
/// walking in stop access. A stop is walk-linked to the nearest walkable street, which need not
/// permit cars. The vehicle providing on-demand service may have to drop the rider off at a
/// different street than the one the stop is linked to.
///
/// Some methods are disabled because they require a rider to walk from a car-based service to a
/// transit stop. They should be enabled when the mechanism for handling that is finalized.
public class OnDemandStopAccessTest {

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    /// One street, one stop beside it, and a drop-off polygon at the street's east end. The two
    /// variants of this scene are illustrated in drivableStreetNetwork10m.svg and
    /// drivableStreetNetwork200m.svg alongside this source file (see SceneDiagramWriter).
    static TransportNetwork drivableStreetNetwork (Scene scene, int stopDistance) {
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).east(1000);
        SceneStop stop = scene.stop("curb", 500, stopDistance);
        ScenePolygon zone = scene.rectPolygon("zone", 900, -50, 1100, 50);
        scene.onDemand("flexOut")
            .fromStops(stop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zone).dropOffWindow(WINDOW_START, WINDOW_END);
        return scene.buildNetwork();
    }

    /// A stop whose walk link already reaches a car-permitting street needs no separate
    /// car connector for on-demand pick-up. Walking from the origin to the zone would take over
    /// 400 seconds, so a time under 300 proves the transit option was used.
    @Test
    void onDemandPickupOnDrivableStreet () {
        Scene scene = new Scene();
        TransportNetwork network = drivableStreetNetwork(scene, 10);
        StreetRouter router = routeWithOnDemand(network, scene, 450, 0, onDemand(network, "flexOut"));
        double expectedToStop = (50 + 10) / WALK_SPEED;
        assertEquals(expectedToStop, router.getReachedStops().get(stopIndex(network, "curb")), 5,
            "The stop should be reached by walking 50 meters of street and the 10 meter link.");
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        assertTrue(timeToZone < 300,
            "On-demand service from the stop should reach the zone far faster than walking, but took "
                + timeToZone + " seconds.");
    }

    /// The rider physically walks the gap between the street and the platform, so that
    /// hop should be priced at walking pace in both directions. An on-demand ride out of a stop
    /// should traverse the link edge in about 150 seconds on foot, not 14 seconds at the default
    /// car speed of 50 km/h.
    @Test
    @Disabled("Needs car connectors with walk costs between stops and drivable streets.")
    void walkToPickupStopAtWalkingPace () {
        Scene scene = new Scene();
        TransportNetwork network = drivableStreetNetwork(scene, 200);
        StreetRouter router = routeWithOnDemand(network, scene, 500, 0, onDemand(network, "flexOut"));
        int timeToStop = router.getReachedStops().get(stopIndex(network, "curb"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        assertTrue(timeToZone - timeToStop >= 200 / WALK_SPEED,
            "Leaving the stop should charge the 200 meter platform walk at walking pace, but "
                + "the zone was reached only " + (timeToZone - timeToStop) + " seconds after the stop.");
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
        StreetRouter router = routeWithOnDemand(network, scene, 950, 0, onDemand(network, "toStop"));
        int timeToStop = router.getReachedStops().get(stopIndex(network, "plaza-stop"));
        assertTrue(timeToStop > 0 && timeToStop < 400,
            "The ride and a 160 meter walk should reach the stop well before a pure walk, but took "
                + timeToStop + " seconds.");
        int timeToPlazaEnd = router.getTravelTimeToVertex(vertexAt(network, scene, 600, 50));
        assertTrue(timeToPlazaEnd < 400,
            "Walking should continue past the drop-off into the plaza, but its far end took "
                + timeToPlazaEnd + " seconds.");
    }

    /// On-demand pickup at a stop whose walk link attaches to a car-free plaza must allow the
    /// vehicle to leave from the closest drivable street the pedestrian can walk to.
    @Test
    @Disabled("Needs car connectors incurring walk times between stops and drivable streets.")
    void pickupAtStopOnCarHostileStreetRidesOut () {
        Scene scene = new Scene();
        TransportNetwork network = plazaNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 550, 50, onDemand(network, "fromStop"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 1000, 0));
        assertTrue(timeToZone >= 150 && timeToZone <= 450,
            "Riding out from the plaza stop should include the walk to the drivable street "
                + "plus the drive to the zone, but took " + timeToZone + " seconds.");
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
        StreetRouter router = routeWithOnDemand(network, scene, 950, 0, onDemand(network, "toIsland"));
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
        StreetRouter router = routeWithOnDemand(network, scene, 1150, 1100, onDemand(network, "fromIsland"));
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

    /// Car access to the station must involve a drop-off at the best street a pedestrian can
    /// actually walk to, which is Access Road 310 meters away through the plaza and footpath.
    /// That walk should take the correct amount of time for walking pace. The frontage road 20
    /// meters away across a barrier should not be used. The lower bound excludes the frontage
    /// road shortcut, which would reach the zone in about 200 seconds. The upper bound excludes
    /// walking all the way, which takes about 1740 seconds.
    @Test
    @Disabled("Needs car connectors that incur walk time between stops and drivable streets.")
    void riderWalksFromCarToStation () {
        Scene scene = new Scene();
        TransportNetwork network = stationNetwork(scene);
        StreetRouter router = routeWithOnDemand(network, scene, 550, 110, onDemand(network, "taxiOut"));
        int timeToZone = router.getTravelTimeToVertex(vertexAt(network, scene, 2000, 0));
        assertTrue(timeToZone >= 380 && timeToZone <= 800,
            "The taxi ride should start at Access Rd after a 310 meter priced walk and drive "
                + "around via Main St, but the zone was reached in " + timeToZone + " seconds.");
    }

}
