package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;

import static com.conveyal.r5.analyst.network.scene.AddOnDemandTest.feederNetwork;
import static com.conveyal.r5.analyst.network.scene.AddOnDemandTest.feederScenario;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.minutesTo;
import static com.conveyal.r5.analyst.network.scene.SceneModifications.useFileStorage;
import static com.conveyal.r5.api.util.LegMode.BICYCLE;
import static com.conveyal.r5.api.util.LegMode.CAR;
import static com.conveyal.r5.api.util.LegMode.ON_DEMAND;
import static com.conveyal.r5.api.util.LegMode.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of the ON_DEMAND leg mode in complete travel time computations without scheduled
/// transit, on the feeder scene of AddOnDemandTest with a one minute pick-up wait. Walking the
/// 2000 meters from the zone to the hub takes over 25 minutes and cycling over 8, while on-demand
/// takes about a minute of waiting plus a few minutes of driving. Times are in whole minutes.
/// Tests the request-level behavior using all the machinery together (as opposed to unit tests).
public class OnDemandLegModeTest {

    @TempDir
    Path tempDir;

    private static final double[] HUB = {2500, 20};

    @BeforeEach
    void fileStorage () {
        useFileStorage(tempDir);
    }

    private static TransportNetwork scenario (Scene scene) {
        return feederScenario(feederNetwork(scene), scene, 1);
    }

    /// Services are used only when the request includes the ON_DEMAND leg mode.
    @Test
    void onDemandOnlyWhenRequested () {
        Scene scene = new Scene();
        TransportNetwork network = scenario(scene);
        int walked = minutesTo(network, scene, 500, 20, EnumSet.of(WALK), HUB)[0];
        int ridden = minutesTo(network, scene, 500, 20, EnumSet.of(WALK, ON_DEMAND), HUB)[0];
        assertTrue(walked >= 25, "Walking to the hub should take over 25 minutes, not " + walked);
        assertTrue(ridden <= 6, "Walking to the pick-up and riding should take a few minutes, not " + ridden);
    }

    /// Bicycle searches also seed rides. Cycling to the hub takes over eight minutes.
    @Test
    void bicycleSeedsRides () {
        Scene scene = new Scene();
        TransportNetwork network = scenario(scene);
        int cycled = minutesTo(network, scene, 500, 20, EnumSet.of(BICYCLE), HUB)[0];
        int ridden = minutesTo(network, scene, 500, 20, EnumSet.of(BICYCLE, ON_DEMAND), HUB)[0];
        assertTrue(cycled >= 8, "Cycling to the hub should take over 8 minutes, not " + cycled);
        assertTrue(ridden <= 6, "Cycling to the pick-up and riding should take a few minutes, not " + ridden);
    }

    /// A rider outside every pick-up polygon still has ordinary street access with ON_DEMAND
    /// enabled. A destination 100 meters away is reached on foot in a minute or two.
    @Test
    void outsideEveryZoneKeepsWalkAccess () {
        Scene scene = new Scene();
        TransportNetwork network = scenario(scene);
        int minutes = minutesTo(network, scene, 1500, 20, EnumSet.of(WALK, ON_DEMAND), new double[] {1400, 20})[0];
        assertTrue(minutes <= 2, "A nearby destination should be reached on foot, not in " + minutes + " minutes.");
    }

    /// ON_DEMAND requires WALK or BICYCLE in the same mode set to reach the pick-up place.
    /// CAR alone does not satisfy that requirement.
    @Test
    void onDemandRequiresWalkOrBicycle () {
        Scene scene = new Scene();
        TransportNetwork network = scenario(scene);
        assertThrows(IllegalArgumentException.class, () ->
            minutesTo(network, scene, 500, 20, EnumSet.of(ON_DEMAND), HUB));
        assertThrows(IllegalArgumentException.class, () ->
            minutesTo(network, scene, 500, 20, EnumSet.of(CAR, ON_DEMAND), HUB));
        // Car access alongside walk-plus-on-demand access is allowed and compared side by side.
        int minutes = minutesTo(network, scene, 500, 20, EnumSet.of(CAR, WALK, ON_DEMAND), HUB)[0];
        assertTrue(minutes <= 6, "Driving or riding to the hub should take a few minutes, not " + minutes);
    }

    /// ON_DEMAND among the egress modes is accepted and has no effect on a request without
    /// scheduled transit, since egress legs only follow transit rides. Egress rides after
    /// transit are tested in OnDemandTransitEgressTest.
    @Test
    void egressModeWithoutTransit () {
        Scene scene = new Scene();
        TransportNetwork network = scenario(scene);
        int without = minutesTo(network, scene, 500, 20, EnumSet.of(WALK, ON_DEMAND), HUB)[0];
        int with = minutesTo(network, scene, 500, 20,
            EnumSet.of(WALK, ON_DEMAND), EnumSet.of(WALK, ON_DEMAND), HUB)[0];
        assertEquals(without, with);
    }

}
