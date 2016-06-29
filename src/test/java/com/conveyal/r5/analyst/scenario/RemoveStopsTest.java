package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

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

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rs);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // should not have affected base network
        assertTrue(network.transitLayer.tripPatterns.stream()
                .anyMatch(tp -> tp.stops.length == 3 && "MULTIPLE_PATTERNS:s2".equals(network.transitLayer.stopIdForIndex.get(tp.stops[1]))));

        // but no trips should stop at s2
        assertFalse(mod.transitLayer.tripPatterns.stream()
                .anyMatch(tp -> tp.stops.length == 3 && "MULTIPLE_PATTERNS:s2".equals(mod.transitLayer.stopIdForIndex.get(tp.stops[1]))));

        // make sure the times are correct
        // One trip pattern did not ever stop at s2, while one trip pattern had s2 removed
        TripPattern original = null, modified = null;

        assertEquals(2, mod.transitLayer.tripPatterns.size());

        for (TripPattern pattern : mod.transitLayer.tripPatterns) {
            if (pattern.tripSchedules.stream().anyMatch(ts -> "MULTIPLE_PATTERNS:trip25200".equals(ts.tripId))) {
                // two-stop trip
                original = pattern;
            } else {
                modified = pattern;
            }
        }

        assertNotNull(original);
        assertNotNull(modified);

        for (TripSchedule schedule : original.tripSchedules) {
            // confirm the times are correct
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            assertEquals("MULTIPLE_PATTERNS:trip" + schedule.arrivals[0], schedule.tripId);

            assertArrayEquals(new int[] { 0, 500  }, a);
            assertArrayEquals(new int[] { 0, 500 }, d);
        }

        for (TripSchedule schedule : modified.tripSchedules) {
            // confirm the times are correct
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            assertEquals("MULTIPLE_PATTERNS:trip" + schedule.arrivals[0], schedule.tripId);

            // dwell time should be gone but travel time should remain
            assertArrayEquals(new int[] { 0, 1000  }, a);
            assertArrayEquals(new int[] { 0, 1000 }, d);
        }

        // network checksum should not change
        assertEquals(checksum, network.checksum());
    }

    @After
    public void tearDown () {
        this.network = null;
    }
}
