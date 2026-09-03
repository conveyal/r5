package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;

import static com.conveyal.r5.analyst.network.scene.AddOnDemandTest.HUB_RECT;
import static com.conveyal.r5.analyst.network.scene.AddOnDemandTest.ZONE_RECT;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.Area;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.addOnDemand;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.apply;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.minutesByTransit;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.service;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.useFileStorage;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.writePolygons;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.NOON;
import static com.conveyal.r5.api.util.LegMode.ON_DEMAND;
import static com.conveyal.r5.api.util.LegMode.WALK;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of riding on-demand services as egress legs after scheduled transit.
/// The rider takes a bus from the far junction to a stop at the hub, where a service
/// whose pick-up place contains that stop carries them onward into its drop-off place.
public class OnDemandTransitEgressTest {

    @TempDir
    Path tempDir;

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    private static final double[] FAR = {12500, 20};

    private static final double[] ZONE = {500, 20};

    private static final double[] WEST = {-500, 20};

    /// Pieces of the shared scene that tests reference when declaring their on-demand services.
    record Feeder (SceneStop hubStop, ScenePolygon zonePolygon, ScenePolygon hubPolygon) { }

    /// Add a bus every ten minutes from the far junction to the hub, taking ten minutes.
    /// Each test declares its own on-demand service before building the network.
    /// Walking from the hub to the zone junction takes about 25 minutes,
    /// so an egress ride is much faster than walking onward.
    static Feeder feederWithReturnBus (Scene scene) {
        AddOnDemandTest.feederStreets(scene);
        SceneStop hubStop = scene.stop("hub-stop", 2510, 10);
        SceneStop farStop = scene.stop("far-stop", 12510, 10);
        ScenePolygon zonePolygon = scene.rectPolygon("zone", ZONE_RECT[0], ZONE_RECT[1], ZONE_RECT[2], ZONE_RECT[3]);
        ScenePolygon hubPolygon = scene.rectPolygon("hub", HUB_RECT[0], HUB_RECT[1], HUB_RECT[2], HUB_RECT[3]);
        scene.route("bus").stops(farStop, hubStop).hopSeconds(600).departures(WINDOW_START, WINDOW_END, 600);
        return new Feeder(hubStop, zonePolygon, hubPolygon);
    }

    /// Layer on a flex service from the hub polygon to the zone polygon.
    /// This is the canonical scene of this class, for which we render a diagram.
    /// Tests needing a variant service declare their own on feederWithReturnBus.
    static TransportNetwork feederEgressNetwork (Scene scene) {
        Feeder feeder = feederWithReturnBus(scene);
        scene.onDemand("flex")
            .fromPolygon(feeder.hubPolygon()).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(feeder.zonePolygon()).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60);
        return scene.buildNetwork();
    }

    @BeforeEach
    void fileStorage () {
        useFileStorage(tempDir);
    }

    /// A rider alighting at the hub stop, which lies in the flex service's pick-up polygon, is
    /// carried into the zone. The egress ride is used only when the request includes the
    /// ON_DEMAND egress leg mode. With walk egress alone the zone is out of reach.
    @Test
    void flexEgressAfterScheduledTransit () {
        Scene scene = new Scene();
        TransportNetwork network = feederEgressNetwork(scene);
        int walked = minutesByTransit(network, scene, FAR[0], FAR[1], EnumSet.of(WALK), ZONE)[0];
        int ridden = minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(walked >= 35, "With walk egress alone the zone should be far out of reach, not " + walked);
        assertTrue(ridden <= 25, "Bus then egress ride should reach the zone quickly, not in " + ridden);
        assertTrue(ridden >= 12, "The bus ride and wait alone take over 12 minutes, not " + ridden);
    }

    /// The egress ride delivers riders only into the service's drop-off place. A destination at
    /// the west junction, outside the drop-off polygon, gains nothing from the service even
    /// though the vehicle could physically drive there.
    @Test
    void dropOffPlaceClipsEgressTargets () {
        Scene scene = new Scene();
        TransportNetwork network = feederEgressNetwork(scene);
        int[] ridden = minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE, WEST);
        assertTrue(ridden[0] <= 25, "The in-zone destination is reached by the egress ride, not in " + ridden[0]);
        assertTrue(ridden[1] >= 35, "The west junction is outside the drop-off place, so " + ridden[1]
            + " minutes should reflect walking only.");
    }

    /// A location-group service picks up alighting riders at its member stops, which includes the
    /// hub stop where the rider alights.
    @Test
    void locationGroupPickUpEgress () {
        Scene scene = new Scene();
        Feeder feeder = feederWithReturnBus(scene);
        scene.onDemand("flex")
            .fromStops(feeder.hubStop()).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(feeder.zonePolygon()).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60);
        TransportNetwork network = scene.buildNetwork();
        int ridden = minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(ridden <= 25, "Bus then egress ride should reach the zone quickly, not in " + ridden);
        assertTrue(ridden >= 12, "The bus ride and wait alone take over 12 minutes, not " + ridden);
    }

    /// A service whose pick-up window closes before the rider can arrive at the stop is unusable
    /// on egress, leaving only walking.
    @Test
    void pickUpWindowClosedBeforeArrival () {
        Scene scene = new Scene();
        Feeder feeder = feederWithReturnBus(scene);
        scene.onDemand("flex")
            .fromPolygon(feeder.hubPolygon()).pickupWindow(WINDOW_START, NOON - 3600)
            .toPolygon(feeder.zonePolygon()).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60);
        TransportNetwork network = scene.buildNetwork();
        int ridden = minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(ridden >= 35, "The pick-up window closed before any arrival, so " + ridden
            + " minutes should reflect walking only.");
    }

    /// The egress ride duration is scaled by the service's duration factor.
    @Test
    void durationFactorScalesEgressRide () {
        int direct = egressMinutesWithFactor(1);
        int scaled = egressMinutesWithFactor(10);
        assertTrue(scaled >= direct + 4, "A tenfold duration factor should slow the 2000 meter ride "
            + "by several minutes, not from " + direct + " to " + scaled);
    }

    private int egressMinutesWithFactor (double factor) {
        Scene scene = new Scene();
        Feeder feeder = feederWithReturnBus(scene);
        scene.onDemand("flex")
            .fromPolygon(feeder.hubPolygon()).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(feeder.zonePolygon()).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60).durationFactor(factor);
        TransportNetwork network = scene.buildNetwork();
        return minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
    }

    /// Add a bus from the far junction to "gate-stop" set back from the drivable network.
    /// A footpath runs from the hub junction diagonally to (2600, 100) and north to (2600, 350),
    /// with gate-stop at its end. The nearest drivable street in the stop's meeting area is a
    /// 400 meter walk away at the hub junction. A location-group flex service picks up at the gate
    /// stop and drops off in the zone polygon.
    static TransportNetwork setBackStopNetwork (Scene scene) {
        return setBackStopNetwork(scene, 1);
    }

    static TransportNetwork setBackStopNetwork (Scene scene, double durationFactor) {
        AddOnDemandTest.feederStreets(scene);
        SceneStop farStop = scene.stop("far-stop", 12510, 10);
        SceneStop gateStop = scene.stop("gate-stop", 2610, 360);
        ScenePolygon zonePolygon = scene.rectPolygon("zone", ZONE_RECT[0], ZONE_RECT[1], ZONE_RECT[2], ZONE_RECT[3]);
        scene.way(WayPreset.FOOTPATH).named("Gate Path").from(scene.junction("hub")).step(100, 100).north(250);
        scene.onDemand("flex")
            .fromStops(gateStop).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(zonePolygon).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60).durationFactor(durationFactor);
        scene.route("bus").stops(farStop, gateStop).hopSeconds(600).departures(WINDOW_START, WINDOW_END, 600);
        return scene.buildNetwork();
    }

    /// A rider alighting at the set-back gate-stop walks 400 meters to meet the vehicle at the
    /// hub junction before the pick-up wait and the ride. That walk adds around five minutes
    /// over what the same trip would take if the vehicle met the rider at the stop itself.
    @Test
    void setBackPickUpStop () {
        Scene scene = new Scene();
        TransportNetwork network = setBackStopNetwork(scene);
        int ridden = minutesByTransit(network, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(ridden >= 22, "The egress ride includes a 400 meter walk to meet the vehicle, "
            + "so the trip should take at least 22 minutes, not " + ridden);
        assertTrue(ridden <= 30, "Bus then walk, wait and ride should stay under 30 minutes, not " + ridden);
    }

    /// The duration factor scales only the car portion of the stored egress ride, never the walk
    /// to the meeting point. On the set-back scene the walk is over five minutes and the ride
    /// around two and a half, so a factor of five adds roughly ten minutes. Scaling the walk
    /// as well would add over twenty more, which the upper bound rejects.
    @Test
    void factorScalesRideNotWalk () {
        Scene direct = new Scene();
        int unscaled = minutesByTransit(setBackStopNetwork(direct), direct, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        Scene scaled = new Scene();
        int fivefold = minutesByTransit(setBackStopNetwork(scaled, 5), scaled, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(fivefold >= unscaled + 8, "A fivefold factor should slow the ride portion by "
            + "around ten minutes, not from " + unscaled + " to " + fivefold);
        assertTrue(fivefold <= unscaled + 16, "The factor must not scale the five minute walk to "
            + "the meeting point, but the trip went from " + unscaled + " to " + fivefold);
    }

    /// An on-demand service created by a modification transports alighting transit riders in the
    /// same way as one derived from a GTFS feed that is active on every date. The base network has
    /// no on-demand service at all, and requesting on-demand egress there falls back to walking.
    @Test
    void modificationServiceCarriesEgressRiders () {
        Scene scene = new Scene();
        feederWithReturnBus(scene);
        TransportNetwork base = scene.buildNetwork();
        String polygons = writePolygons(scene, "egress.geojson", new Area("zone", ZONE_RECT), new Area("hub", HUB_RECT));
        TransportNetwork scenario = apply(base, "egress", addOnDemand(polygons, service("hub", "zone", 1)));
        int onBase = minutesByTransit(base, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        int onScenario = minutesByTransit(scenario, scene, FAR[0], FAR[1],
            EnumSet.of(WALK), EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(onBase >= 35, "Without any service the zone is reached on foot, not in " + onBase + " minutes.");
        assertTrue(onScenario <= 25, "The modification's service should carry riders from the bus, not in " + onScenario);
    }

}
