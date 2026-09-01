package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.geom.CMultiPolygon;
import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.scenario.AddOnDemand;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.conveyal.r5.analyst.network.scene.SceneModifications.Area;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.addOnDemand;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.apply;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.service;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.useFileStorage;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.writePolygons;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of the AddOnDemand modification. Polygons from a data source are resolved into on-demand
/// services in a scenario network, including parameter validation and routing. Street routing here
/// uses the same pipeline as production uses for on-demand, but for only one service at a time.
/// Here we assert things that are not observable through travel times alone.
/// Tests of the complete travel time computation are in OnDemandLegModeTest.
public class AddOnDemandTest {

    @TempDir
    Path tempDir;

    /// The street layout shared by this class and OnDemandTransitTest. One long east-west road
    /// with short cross streets at four junctions, West at x = -500, Zone at x = 500, Hub at
    /// x = 2500 and Far at x = 12500. This method adds only streets to the scene. Callers add any
    /// transit or on-demand services before building the network.
    static void feederStreets (Scene scene) {
        SceneJunction west = scene.junction("west", -500, 0);
        SceneJunction zone = scene.junction("zone", 500, 0);
        SceneJunction hub = scene.junction("hub", 2500, 0);
        SceneJunction far = scene.junction("far", 12500, 0);
        scene.way(WayPreset.STREET).named("Main Rd").from(-1000, 0).via(west).via(zone).via(hub).via(far).east(500);
        scene.way(WayPreset.STREET).named("West St").from(-500, -100).via(west).north(100);
        scene.way(WayPreset.STREET).named("Zone St").from(500, -100).via(zone).north(100);
        scene.way(WayPreset.STREET).named("Hub St").from(2500, -100).via(hub).north(100);
        scene.way(WayPreset.STREET).named("Far St").from(12500, -100).via(far).north(100);
    }

    /// The shared feeder streets with no transit and no on-demand service. The far junction plays
    /// no role in this class. These tests use the zone and hub junctions 2000 meters apart.
    static TransportNetwork feederNetwork (Scene scene) {
        feederStreets(scene);
        return scene.buildNetwork();
    }

    static final int[] ZONE_RECT = {300, -150, 700, 150};
    static final int[] HUB_RECT = {2300, -150, 2700, 150};
    static final int[] WEST_RECT = {-700, -150, -300, 150};

    /// Write a standard polygon layer. Features are "zone" and "hub" around street junctions,
    /// and "split" which is a multipolygon made of the "west" and "zone" rectangles.
    static String feederPolygons (Scene scene) {
        return writePolygons(scene, "feeder.geojson",
            new Area("zone", ZONE_RECT),
            new Area("hub", HUB_RECT),
            new Area("split", WEST_RECT, ZONE_RECT));
    }

    /// Apply a modification adding service from "zone" to "hub" with the given wait, and the
    /// reverse direction with a two minute wait.
    static TransportNetwork feederScenario (TransportNetwork base, Scene scene, double waitMinutes) {
        String polygons = feederPolygons(scene);
        AddOnDemand modification = addOnDemand(polygons,
            service("zone", "hub", waitMinutes),
            service("hub", "zone", 2));
        return apply(base, "feeder", modification);
    }

    @BeforeEach
    void fileStorage () {
        useFileStorage(tempDir);
    }

    @Test
    void servicesAddedToScenarioOnly () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        TransportNetwork scenario = feederScenario(base, scene, 5);
        assertNull(base.transitLayer.onDemandIndex, "The base network has no on-demand services.");
        List<OnDemand> services = scenario.transitLayer.onDemandIndex.allServices();
        assertEquals(2, services.size());
        OnDemand out = onDemand(scenario, "zone>hub");
        OnDemand back = onDemand(scenario, "hub>zone");
        assertEquals(300, out.durationOffset, "The wait is stored in seconds.");
        assertEquals(120, back.durationOffset);
        assertEquals(1, out.durationFactor);
        assertEquals(0, out.fromWindowStart);
        assertEquals(Integer.MAX_VALUE, out.fromWindowEnd);
        assertTrue(out.alwaysActive && back.alwaysActive,
            "Services added by a modification are active on every date.");
        assertEquals(-1, out.serviceCode, "An always-active service is backed by no calendar.");
        assertNull(out.serviceId);
    }

    /// From an origin next to the "zone" junction, an on-demand ride to the "hub" junction with a
    /// five minute wait is much faster than walking the 2000 meters.
    @Test
    void rideFromZoneToHub () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        TransportNetwork scenario = feederScenario(base, scene, 5);
        var results = SceneRouting.routeWithOnDemand(scenario, scene, 500, 20, onDemand(scenario, "zone>hub"));
        int seconds = results.getTravelTimeToVertex(vertexAt(scenario, scene, 2500, 0));
        assertTrue(seconds > 300, "The wait alone is 300 seconds, but the hub was reached in " + seconds);
        assertTrue(seconds < 900, "Walk, wait and ride should take well under 900 seconds, not " + seconds);
    }

    /// Services are directional. From "hub", only the service declared from "hub" to "zone"
    /// carries riders to "zone". In the other direction they must walk.
    @Test
    void directional () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        TransportNetwork scenario = feederScenario(base, scene, 5);
        int zoneVertex = vertexAt(scenario, scene, 500, 0);
        var back = SceneRouting.routeWithOnDemand(scenario, scene, 2500, 20, onDemand(scenario, "hub>zone"));
        var out = SceneRouting.routeWithOnDemand(scenario, scene, 2500, 20, onDemand(scenario, "zone>hub"));
        int ridden = back.getTravelTimeToVertex(zoneVertex);
        int walked = out.getTravelTimeToVertex(zoneVertex);
        assertTrue(ridden < 900, "The hub-to-zone service should carry the rider to the zone, not " + ridden);
        assertTrue(walked > 1400, "The zone-to-hub service cannot be ridden from the hub, so the 2000 meters "
            + "should be walked, but the zone was reached in " + walked);
    }

    /// A multipolygon zone is one service. A rider in its western part boards there rather than
    /// walking 1000 meters to the other part.
    @Test
    void multiPolygonZone () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        String polygons = feederPolygons(scene);
        TransportNetwork scenario = apply(base, "split", addOnDemand(polygons, service("split", "hub", 1)));
        OnDemand od = onDemand(scenario, "split>hub");
        assertTrue(od.fromPolygon instanceof CMultiPolygon);
        var results = SceneRouting.routeWithOnDemand(scenario, scene, -500, 20, od);
        int seconds = results.getTravelTimeToVertex(vertexAt(scenario, scene, 2500, 0));
        assertTrue(seconds < 700, "Boarding in the western part of the zone should reach the hub in "
            + "well under 700 seconds, not " + seconds);
    }

    /// Modification-level waitMinutes and durationFactor apply to services that do not state
    /// their own, and a service's own values override them field by field.
    @Test
    void modificationLevelDefaults () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        String polygons = feederPolygons(scene);
        AddOnDemand modification = addOnDemand(polygons, service("zone", "hub", 2), service("hub", "zone"));
        modification.waitMinutes = 5;
        modification.durationFactor = 1.5;
        TransportNetwork scenario = apply(base, "defaults", modification);
        OnDemand inherited = onDemand(scenario, "hub>zone");
        assertEquals(300, inherited.durationOffset, "A service stating no wait inherits the modification's.");
        assertEquals(1.5, inherited.durationFactor);
        OnDemand overridden = onDemand(scenario, "zone>hub");
        assertEquals(120, overridden.durationOffset, "A service's own wait overrides the modification's.");
        assertEquals(1.5, overridden.durationFactor, "A service stating only the wait still inherits the factor.");
    }

    /// The duration factor scales the ride. With no wait, a ride with factor three takes about
    /// three times as long as with factor one, with the short walk to the pick-up being constant.
    @Test
    void durationFactorScalesRide () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        String polygons = feederPolygons(scene);
        AddOnDemand.Service slow = service("zone", "hub", 0);
        slow.durationFactor = 3.0;
        TransportNetwork plain = apply(base, "plain", addOnDemand(polygons, service("zone", "hub", 0)));
        TransportNetwork scaled = apply(base, "scaled", addOnDemand(polygons, slow));
        int hub = vertexAt(base, scene, 2500, 0);
        int plainSeconds = SceneRouting.routeWithOnDemand(plain, scene, 500, 20, onDemand(plain, "zone>hub"))
            .getTravelTimeToVertex(hub);
        int scaledSeconds = SceneRouting.routeWithOnDemand(scaled, scene, 500, 20, onDemand(scaled, "zone>hub"))
            .getTravelTimeToVertex(hub);
        double ratio = (double) scaledSeconds / plainSeconds;
        assertTrue(ratio > 2.5 && ratio < 3.1, String.format(
            "A factor of three should roughly triple the ride (%d vs %d seconds, ratio %.2f).",
            scaledSeconds, plainSeconds, ratio));
    }

    @Test
    void twoModificationsInOneScenario () {

        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        String polygons = feederPolygons(scene);
        TransportNetwork scenario = apply(base, "two",
            addOnDemand(polygons, service("zone", "hub", 1)),
            addOnDemand(polygons, service("hub", "zone", 1)));
        OnDemand out = onDemand(scenario, "zone>hub");
        OnDemand back = onDemand(scenario, "hub>zone");
        assertTrue(out.alwaysActive && back.alwaysActive);
        assertEquals(2, scenario.transitLayer.onDemandIndex.size());
    }

    @Test
    void missingParameters () {
        Scene scene = new Scene();
        TransportNetwork base = feederNetwork(scene);
        AddOnDemand noServices = addOnDemand(feederPolygons(scene));
        assertTrue(noServices.resolve(base));
        assertTrue(String.join("\n", noServices.errors).contains("At least one service"));
        AddOnDemand noPolygons = addOnDemand(null, service("zone", "hub", 1));
        assertTrue(noPolygons.resolve(base));
        assertTrue(String.join("\n", noPolygons.errors).contains("polygon data source"));
        AddOnDemand missingFile = addOnDemand("absent.geojson", service("zone", "hub", 1));
        assertTrue(missingFile.resolve(base));
        assertFalse(missingFile.errors.isEmpty(), "A missing data source should be reported, not thrown.");
    }

}
