package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;
import static com.conveyal.r5.analyst.scenario.FakeGraph.*;

/**
 * Created by matthewc on 4/21/16.
 */
public class RemoveStopsTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_PATTERNS);
        checksum = network.checksum();
    }

    @Test
    public void testRemoveStops () {
        // ensure that some trips stop at s2
        assertTrue(network.transitLayer.tripPatterns.stream()
                .anyMatch(tp -> tp.stops.length == 3 && "MULTIPLE_PATTERNS:s2".equals(network.transitLayer.stopIdForIndex.get(tp.stops[1]))));

        // remove stop 2
        RemoveStops rs = new RemoveStops();
        rs.stops = set("MULTIPLE_PATTERNS:s2");
        rs.routes = set("MULTIPLE_PATTERNS:route");

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(rs);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // should not have affected base network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .anyMatch(tp -> tp.stops.length == 3 && "MULTIPLE_PATTERNS:s2".equals(network.transitLayer.stopIdForIndex.get(tp.stops[1]))));

        // but no trips should stop at s2
        assertFalse(mod.transitLayer.tripPatterns.stream()
                .anyMatch(tp -> tp.stops.length == 3 && "MULTIPLE_PATTERNS:s2".equals(mod.transitLayer.stopIdForIndex.get(tp.stops[1]))));

        // network checksum should not change
        assertEquals(checksum, network.checksum());
    }

    @After
    public void tearDown () {
        this.network = null;
    }
}
