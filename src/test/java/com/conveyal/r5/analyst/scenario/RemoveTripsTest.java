package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test that removing trips works correctly.
 */
public class RemoveTripsTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_LINES);
        checksum = network.checksum();
    }

    @Test
    public void testRemoveByRoute () {
        // there should be 78 trips on each route (6 per hour from 7 am to 8 pm)
        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        RemoveTrips rt = new RemoveTrips();
        rt.routes = set("MULTIPLE_LINES:route"); // remove one of the routes

        // make sure it applies cleanly
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rt);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // there should still be trips on the retained route, but none on the removed route
        assertEquals(0, mod.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(78, mod.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        // should not have affected original network
        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(checksum, network.checksum());
    }

    @Test
    public void testRemoveSpecificTrips () {
        // there should be 78 trips on each route (6 per hour from 7 am to 8 pm)
        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        RemoveTrips rt = new RemoveTrips();
        rt.trips = set("MULTIPLE_LINES:tripb25200"); // 7am trip on route2

        // make sure it applies cleanly
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rt);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(78, mod.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        // we removed one trip here
        assertEquals(77, mod.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        // make sure the trip is gone
        assertTrue(mod.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .flatMap(p -> p.tripSchedules.stream())
                .noneMatch(t -> "MULTIPLE_LINES:tripb25200".equals(t.tripId)));

        // should not have affected original network
        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(78, network.transitLayer.tripPatterns.stream()
                .filter(p -> "MULTIPLE_LINES:route2".equals(p.routeId))
                .mapToInt(p -> p.tripSchedules.size())
                .sum());

        assertEquals(checksum, network.checksum());
    }

    // don't keep bunches of copies of the network around, JUnit keeps references to all test classes
    // http://blogs.atlassian.com/2005/12/reducing_junit_memory_usage/
    @After
    public void tearDown () {
        this.network = null;
    }
}