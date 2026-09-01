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
import static com.conveyal.r5.analyst.network.scene.SceneModifications.minutesTo;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.service;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.useFileStorage;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.writePolygons;
import static com.conveyal.r5.api.util.LegMode.ON_DEMAND;
import static com.conveyal.r5.api.util.LegMode.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of complete travel time computations on a network containing a GTFS-Flex service, with and
/// without scheduled transit and with a modification adding a second on-demand service. The scene
/// uses the feeder streets shared with AddOnDemandTest, adding a bus route from a stop at the hub
/// to the far junction ten kilometers east.
/// Times are in whole minutes, and travel times involving scheduled transit are medians.
public class OnDemandTransitTest {

    @TempDir
    Path tempDir;

    private static final int WINDOW_START = 6 * 3600;

    private static final int WINDOW_END = 22 * 3600;

    private static final double[] HUB = {2500, 20};

    private static final double[] FAR = {12500, 20};

    private static final double[] ZONE = {500, 20};

    /// The streets shared with AddOnDemandTest plus a flex service from the zone to the hub with a
    /// one minute wait, and a bus every ten minutes from the hub to the far junction taking ten
    /// minutes. Walking from the zone to the far junction is impossible within the walking limit,
    /// so the far junction is reached only through the bus.
    static TransportNetwork feederTransitNetwork (Scene scene) {
        AddOnDemandTest.feederStreets(scene);
        SceneStop hubStop = scene.stop("hub-stop", 2510, 10);
        SceneStop farStop = scene.stop("far-stop", 12510, 10);
        ScenePolygon zonePolygon = scene.rectPolygon("zone", ZONE_RECT[0], ZONE_RECT[1], ZONE_RECT[2], ZONE_RECT[3]);
        ScenePolygon hubPolygon = scene.rectPolygon("hub", HUB_RECT[0], HUB_RECT[1], HUB_RECT[2], HUB_RECT[3]);
        scene.onDemand("flex")
            .fromPolygon(zonePolygon).pickupWindow(WINDOW_START, WINDOW_END)
            .toPolygon(hubPolygon).dropOffWindow(WINDOW_START, WINDOW_END)
            .durationOffset(60);
        scene.route("bus").stops(hubStop, farStop).hopSeconds(600).departures(WINDOW_START, WINDOW_END, 600);
        return scene.buildNetwork();
    }

    @BeforeEach
    void fileStorage () {
        useFileStorage(tempDir);
    }

    /// A service from a flex feed is used only when the request includes the ON_DEMAND leg mode.
    @Test
    void flexFeedUsedOnlyWhenRequested () {
        Scene scene = new Scene();
        TransportNetwork network = feederTransitNetwork(scene);
        int walked = minutesTo(network, scene, ZONE[0], ZONE[1], EnumSet.of(WALK), HUB)[0];
        int ridden = minutesTo(network, scene, ZONE[0], ZONE[1], EnumSet.of(WALK, ON_DEMAND), HUB)[0];
        assertTrue(walked >= 25, "Walking to the hub should take over 25 minutes, not " + walked);
        assertTrue(ridden <= 6, "Walking to the pick-up and riding should take a few minutes, not " + ridden);
    }

    /// A flex ride leads into scheduled transit. Without transit the far junction is unreachable.
    /// Walking to the bus takes over 25 minutes before the median five minute wait and ten minute
    /// ride. Riding the flex service to the hub instead takes a few minutes.
    @Test
    void flexRideThenScheduledTransit () {
        Scene scene = new Scene();
        TransportNetwork network = feederTransitNetwork(scene);
        int noTransit = minutesTo(network, scene, ZONE[0], ZONE[1], EnumSet.of(WALK, ON_DEMAND), FAR)[0];
        int walkToBus = minutesByTransit(network, scene, ZONE[0], ZONE[1], EnumSet.of(WALK), FAR)[0];
        int flexToBus = minutesByTransit(network, scene, ZONE[0], ZONE[1], EnumSet.of(WALK, ON_DEMAND), FAR)[0];
        assertTrue(noTransit > 60, "The far junction should be unreachable without transit, not " + noTransit);
        assertTrue(walkToBus >= 35, "Walking to the bus then riding should take over 35 minutes, not " + walkToBus);
        assertTrue(flexToBus <= 25, "Flex to the bus then riding should take under 25 minutes, not " + flexToBus);
        assertTrue(flexToBus >= 12, "The bus ride and wait alone take over 12 minutes, not " + flexToBus);
    }

    /// A modification adds a service in the direction the feed lacks. Both kinds of service are
    /// found in one scenario network and each carries riders in its own direction.
    @Test
    void feedAndModificationServicesCoexist () {
        Scene scene = new Scene();
        TransportNetwork base = feederTransitNetwork(scene);
        String polygons = writePolygons(scene, "return.geojson", new Area("zone", ZONE_RECT), new Area("hub", HUB_RECT));
        TransportNetwork scenario = apply(base, "return", addOnDemand(polygons, service("hub", "zone", 1)));
        assertEquals(1, base.transitLayer.onDemandIndex.size());
        assertEquals(2, scenario.transitLayer.onDemandIndex.size());
        int out = minutesTo(scenario, scene, ZONE[0], ZONE[1], EnumSet.of(WALK, ON_DEMAND), HUB)[0];
        int back = minutesTo(scenario, scene, HUB[0], HUB[1], EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        int backOnBase = minutesTo(base, scene, HUB[0], HUB[1], EnumSet.of(WALK, ON_DEMAND), ZONE)[0];
        assertTrue(out <= 6, "The feed's service should still carry riders to the hub, not in " + out + " minutes.");
        assertTrue(back <= 6, "The modification's service should carry riders back to the zone, not in " + back + " minutes.");
        assertTrue(backOnBase >= 25, "Without the modification the return trip is walked, not " + backOnBase + " minutes.");
    }

}
