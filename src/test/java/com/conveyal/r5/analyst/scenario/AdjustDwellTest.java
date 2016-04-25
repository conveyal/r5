package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import org.glassfish.grizzly.Transport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test adjusting dwell time.
 */
public class AdjustDwellTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

    @Test
    public void testSetDwellTimeRoute () {
        // confirm that the dwell times are what they should be in the source network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        AdjustDwellTime adt = new AdjustDwellTime();
        adt.dwellSecs = 42;
        adt.routes = set("SINGLE_LINE:route");

        // make sure it will have an effect
        assertNotEquals(FakeGraph.DWELL_TIME, adt.dwellSecs);

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(adt);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // should not have modified original network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        // but should have modified new network
        assertTrue(mod.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(0, 4).allMatch(i -> ts.departures[i] - ts.arrivals[i] == adt.dwellSecs)));

        assertEquals(checksum, network.checksum());
    }

    /** Confirm that setting a dwell time of zero works (since zero is also Java's default value */
    @Test
    public void testSetZeroDwellTimeRoute () {
        // confirm that the dwell times are what they should be in the source network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        AdjustDwellTime adt = new AdjustDwellTime();
        adt.dwellSecs = 0;
        adt.routes = set("SINGLE_LINE:route");

        // make sure it will have an effect
        assertNotEquals(FakeGraph.DWELL_TIME, adt.dwellSecs);

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(adt);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // should not have modified original network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        // but should have modified new network
        assertTrue(mod.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(0, 4).allMatch(i -> ts.departures[i] - ts.arrivals[i] == adt.dwellSecs)));

        assertEquals(checksum, network.checksum());
    }

    /**
     * test scaling dwell times on a segment of a trip (note that this exercises three codepaths that the above tests
     * do not: trip selection rather than route selection, individual stop selection, and scaling).
     */
    @Test
    public void testScaleTripSegmentDwellTime () {
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .anyMatch(ts -> "SINGLE_LINE:trip25200".equals(ts.tripId)));

        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .anyMatch(ts -> !"SINGLE_LINE:trip25200".equals(ts.tripId)));
        
        // confirm that the dwell times are what they should be in the source network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        AdjustDwellTime adt = new AdjustDwellTime();
        adt.scale = 0.5;
        adt.trips = set("SINGLE_LINE:trip25200");
        adt.stops = set("SINGLE_LINE:s2");

        // make sure it's not zero; scaling a zero dwell time will not prove anything
        assertNotEquals(0, FakeGraph.DWELL_TIME);

        Scenario scenario = new Scenario(42);
        scenario.modifications = Arrays.asList(adt);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // should not have modified original network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME)));

        // but should have modified new network
        assertTrue(mod.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .allMatch(ts -> {
                    if ("SINGLE_LINE:trip25200".equals(ts.tripId)) {
                        return ts.departures[1] - ts.arrivals[1] == FakeGraph.DWELL_TIME * adt.scale &&
                                ts.departures[2] - ts.arrivals[2] == FakeGraph.DWELL_TIME;
                    } else {
                        return IntStream.range(1, 3).allMatch(i -> ts.departures[i] - ts.arrivals[i] == FakeGraph.DWELL_TIME);
                    }
                }));

        assertTrue(mod.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .anyMatch(ts -> "SINGLE_LINE:trip25200".equals(ts.tripId)));

        assertTrue(mod.transitLayer.tripPatterns.stream()
                .flatMap(t -> t.tripSchedules.stream())
                .anyMatch(ts -> !"SINGLE_LINE:trip25200".equals(ts.tripId)));
        
        assertEquals(checksum, network.checksum());
    }

    @After
    public void tearDown () {
        network = null;
    }
}
