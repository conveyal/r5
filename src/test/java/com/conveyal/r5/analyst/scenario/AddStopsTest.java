package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;
import static org.junit.Assert.assertEquals;

/**
 * Test adding stops (rerouting)
 */
public class AddStopsTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

    /** Test rerouting a route in the middle */
    @Test
    public void testRerouteInMiddle () {
        AddStops as = new AddStops();
        // skip s3, insert s5
        as.fromStop = "SINGLE_LINE:s2";
        as.toStop = "SINGLE_LINE:s4";
        as.stops = Arrays.asList(new StopSpec("SINGLE_LINE:s5"));
        as.dwellTimes = new int[] { 40 };
        as.hopTimes = new int[] { 60, 70 };
        as.routes = set("SINGLE_LINE:route");

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        TripPattern pattern = mod.transitLayer.tripPatterns.get(0);

        // make sure the stops are in the right order
        assertEquals(4, pattern.stops.length);
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(pattern.stops[0]));
        assertEquals("SINGLE_LINE:s2", mod.transitLayer.stopIdForIndex.get(pattern.stops[1]));
        assertEquals("SINGLE_LINE:s5", mod.transitLayer.stopIdForIndex.get(pattern.stops[2]));
        assertEquals("SINGLE_LINE:s4", mod.transitLayer.stopIdForIndex.get(pattern.stops[3]));

        for (TripSchedule schedule : pattern.tripSchedules) {
            // confirm the times are correct
            int[] a = schedule.arrivals;
            int[] d = schedule.departures;
            assertEquals(d[1] + 60, a[2]);
            assertEquals(a[2] + 40, d[2]);
            assertEquals(d[2] + 70, a[3]);
        }
    }

    /** Test extending a route, not removing any stops */
    @Test
    public void testExtendRouteAtEnd () {
        AddStops as = new AddStops();
        // add s5 at end
        as.fromStop = "SINGLE_LINE:s4";
        as.stops = Arrays.asList(new StopSpec("SINGLE_LINE:s5"));
        as.dwellTimes = new int[] { 40 };
        as.hopTimes = new int[] { 60 };
        as.routes = set("SINGLE_LINE:route");

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(1, mod.transitLayer.tripPatterns.size());

        TripPattern pattern = mod.transitLayer.tripPatterns.get(0);

        // make sure the stops are in the right order
        assertEquals(5, pattern.stops.length);
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(pattern.stops[0]));
        assertEquals("SINGLE_LINE:s2", mod.transitLayer.stopIdForIndex.get(pattern.stops[1]));
        assertEquals("SINGLE_LINE:s3", mod.transitLayer.stopIdForIndex.get(pattern.stops[2]));
        assertEquals("SINGLE_LINE:s4", mod.transitLayer.stopIdForIndex.get(pattern.stops[3]));
        assertEquals("SINGLE_LINE:s5", mod.transitLayer.stopIdForIndex.get(pattern.stops[4]));

        for (TripSchedule schedule : pattern.tripSchedules) {
            // confirm the times are correct
            int[] a = schedule.arrivals;
            int[] d = schedule.departures;
            assertEquals(d[3] + 60, a[4]);
            assertEquals(a[4] + 40, d[4]);
        }
    }

    @After
    public void tearDown () {
        network = null;
    }
}
