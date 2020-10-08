package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.scenario.FakeGraph.TransitNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by matthewc on 4/21/16.
 */
public class RemoveStopsTest {
@Test
    public void testRemoveStops () {
        TransportNetwork network = buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_PATTERNS);
        long checksum = network.checksum();

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

    @Test
    public void testRemoveTime () {
        TransportNetwork network = FakeGraph.buildNetwork(TransitNetwork.SINGLE_LINE);
        RemoveStops rs = new RemoveStops();
        rs.routes = set("SINGLE_LINE:route");
        rs.stops = set("SINGLE_LINE:s2", "SINGLE_LINE:s3");
        rs.secondsSavedAtEachStop = 60;
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rs);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // make sure the stops and times were removed
        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();
            // No dwell at first or last stops. No dwell preserved at removed stops. 1500 travel time (3*500) to go from first to last
            // in base network (two stops, three hops). Remove 120 of that.
            assertArrayEquals(new int[] { 0, 1380 }, a);
            assertArrayEquals(new int[] { 0, 1380 }, d);
        }

        assertTrue(mod.scenarioApplicationWarnings.isEmpty());
    }

    @Test
    public void testRemoveTooMuchTime () {
        TransportNetwork network = FakeGraph.buildNetwork(TransitNetwork.SINGLE_LINE);
        RemoveStops rs = new RemoveStops();
        rs.routes = set("SINGLE_LINE:route");
        rs.stops = set("SINGLE_LINE:s2", "SINGLE_LINE:s3");
        rs.secondsSavedAtEachStop = 1000;
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rs);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // make sure the stops and times were removed
        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();
            // No dwell at first or last stops. No dwell at removed stops. 1500 travel time (3*500) to go from first to last
            // in base network (two stops, three hops). Too much was removed, hop time is clamped to min 1 second.
            assertArrayEquals(new int[] { 0, 1 }, a);
            assertArrayEquals(new int[] { 0, 1 }, d);
        }

        assertEquals(1, mod.scenarioApplicationWarnings.size());
    }

    @Test
    public void testRemoveFirstStop () {
        TransportNetwork network = FakeGraph.buildNetwork(TransitNetwork.SINGLE_LINE);
        RemoveStops rs = new RemoveStops();
        rs.routes = set("SINGLE_LINE:route");
        rs.stops = set("SINGLE_LINE:s1");
        rs.secondsSavedAtEachStop = 60;
        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(rs);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // make sure the stops and times were removed.
        for (int i = 0; i < mod.transitLayer.tripPatterns.get(0).tripSchedules.size(); i++) {
            TripSchedule schedule = mod.transitLayer.tripPatterns.get(0).tripSchedules.get(i);
            // we need the original trip schedule to get the original departure time at the first stop.
            // they should stay in order.
            TripSchedule original = network.transitLayer.tripPatterns.get(0).tripSchedules.get(i);
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - original.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - original.arrivals[0]).toArray();
            // First stop is now at 440 (60 seconds removed from first stop)
            // This is basically assuming the vehicle still follows the full route but deadheads
            // dwell is preserved at new first stop
            assertArrayEquals(new int[] { 440, 970, 1500 }, a);
            assertArrayEquals(new int[] { 470, 1000, 1500 }, d);
        }

        assertTrue(mod.scenarioApplicationWarnings.isEmpty());
    }
}
