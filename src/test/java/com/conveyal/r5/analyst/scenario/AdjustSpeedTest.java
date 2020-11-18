package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static com.conveyal.r5.analyst.scenario.FakeGraph.set;

/**
 * Adjust speed on routes.
 */
public class AdjustSpeedTest {
    public TransportNetwork network;
    public long checksum;

    @BeforeEach
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

    /** Adjust speed on the entire route */
    @Test
    public void adjustSpeedWholeRoute () {
        Assertions.assertEquals(1, network.transitLayer.tripPatterns.size());

        TripSchedule exemplarSchedule = network.transitLayer.tripPatterns.get(0).tripSchedules.get(0);

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = true;

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        Assertions.assertEquals(1, mod.transitLayer.tripPatterns.size());

        // make sure that there still are trip patterns
        Assertions.assertFalse(mod.transitLayer.tripPatterns.get(0).tripSchedules.isEmpty());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            // TODO user-settable zero point for modification
            Assertions.assertEquals("SINGLE_LINE:trip" + schedule.arrivals[0], schedule.tripId);

            // was 500 sec travel, 30 sec dwell, now 250 sec travel and 15 sec dwell
            Assertions.assertArrayEquals(new int[] { 0, 250, 515, 780 }, a);
            Assertions.assertArrayEquals(new int[] { 0, 265, 530, 780 }, d);
        }

        Assertions.assertEquals(checksum, network.checksum());
    }

    /** Adjust speed on the entire route */
    @Test
    public void adjustSpeedWholeRouteButDontAdjustDwells () {
        Assertions.assertEquals(1, network.transitLayer.tripPatterns.size());

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = false;

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        Assertions.assertEquals(1, mod.transitLayer.tripPatterns.size());

        // thrice travel time for three hops, divide by two, and twice dwell time but don't divide by 2.
        int expectedTripLength = FakeGraph.TRAVEL_TIME * 3 / 2 + FakeGraph.DWELL_TIME * 2;

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];
            Assertions.assertEquals(expectedTripLength, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
        }

        // make sure that there still are trip patterns
        Assertions.assertFalse(mod.transitLayer.tripPatterns.get(0).tripSchedules.isEmpty());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            // TODO user-settable zero point for modification
            Assertions.assertEquals("SINGLE_LINE:trip" + schedule.arrivals[0], schedule.tripId);

            // was 500 sec travel, 30 sec dwell, now 250 sec travel and still 30 sec dwell
            Assertions.assertArrayEquals(new int[] { 0, 250, 530, 810 }, a);
            Assertions.assertArrayEquals(new int[] { 0, 280, 560, 810 }, d);
        }


        Assertions.assertEquals(checksum, network.checksum());
    }

    /** Adjust speed on a segment of the route */
    @Test
    public void adjustSpeedSegment () {
        Assertions.assertEquals(1, network.transitLayer.tripPatterns.size());

        AdjustSpeed as = new AdjustSpeed();
        as.routes = set("SINGLE_LINE:route");
        as.scale = 2;
        as.scaleDwells = false;
        as.hops = Arrays.asList(
                new String[] {"SINGLE_LINE:s2", "SINGLE_LINE:s3"},
                new String[] {"SINGLE_LINE:s3", "SINGLE_LINE:s4"}
        );

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        Assertions.assertEquals(1, mod.transitLayer.tripPatterns.size());

        // two hops at half of travel time, one hop at full travel time, and dwell time not scaled
        int expectedTripLength = FakeGraph.TRAVEL_TIME  + FakeGraph.TRAVEL_TIME / 2 * 2 + FakeGraph.DWELL_TIME * 2;

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            // confirm the times are correct
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            // TODO user-settable zero point for modification
            Assertions.assertEquals("SINGLE_LINE:trip" + schedule.arrivals[0], schedule.tripId);

            Assertions.assertArrayEquals(new int[] { 0, 500, 780, 1060 }, a);
            Assertions.assertArrayEquals(new int[] { 0, 530, 810, 1060 }, d);
        }

        Assertions.assertEquals(checksum, network.checksum());
    }

    /** Adjust speed on just one trip */
    @Test
    public void adjustSpeedOneTrip () {
        Assertions.assertEquals(1, network.transitLayer.tripPatterns.size());

        Assertions.assertTrue(network.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> "SINGLE_LINE:trip25200".equals(schedule.tripId)));

        Assertions.assertTrue(network.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> !"SINGLE_LINE:trip25200".equals(schedule.tripId)));

        TripSchedule exemplarSchedule = network.transitLayer.tripPatterns.get(0).tripSchedules.get(0);

        int baseTripLength = exemplarSchedule.departures[exemplarSchedule.departures.length - 1] - exemplarSchedule.arrivals[0];

        AdjustSpeed as = new AdjustSpeed();
        as.trips = set("SINGLE_LINE:trip25200");
        as.scale = 2;
        as.scaleDwells = true;

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(as);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        Assertions.assertEquals(1, mod.transitLayer.tripPatterns.size());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int newTripLength = schedule.departures[schedule.departures.length - 1] - schedule.arrivals[0];

            if ("SINGLE_LINE:trip25200".equals(schedule.tripId))
                Assertions.assertEquals(baseTripLength / 2, newTripLength, 2); // epsilon of 2 seconds to account for rounding errors
            else
                Assertions.assertEquals(baseTripLength, newTripLength);
        }

        // make sure that there are both affected and unaffected trips
        Assertions.assertTrue(mod.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> "SINGLE_LINE:trip25200".equals(schedule.tripId)));

        Assertions.assertTrue(mod.transitLayer.tripPatterns.get(0).tripSchedules.stream()
                .anyMatch(schedule -> !"SINGLE_LINE:trip25200".equals(schedule.tripId)));

        // make sure that there still are trip patterns
        Assertions.assertFalse(mod.transitLayer.tripPatterns.get(0).tripSchedules.isEmpty());

        for (TripSchedule schedule : mod.transitLayer.tripPatterns.get(0).tripSchedules) {
            int[] a = IntStream.of(schedule.arrivals).map(time -> time - schedule.arrivals[0]).toArray();
            int[] d = IntStream.of(schedule.departures).map(time -> time - schedule.arrivals[0]).toArray();

            // slightly awkward, but make sure that the trip starts at the same time as it did before
            // TODO user-settable zero point for modification
            Assertions.assertEquals("SINGLE_LINE:trip" + schedule.arrivals[0], schedule.tripId);

            if ("SINGLE_LINE:trip25200".equals(schedule.tripId)) {
                // was 500 sec travel, 30 sec dwell, now 250 sec travel and 15 sec dwell
                Assertions.assertArrayEquals(new int[]{0, 250, 515, 780}, a);
                Assertions.assertArrayEquals(new int[]{0, 265, 530, 780}, d);
            } else {
                // not modified
                Assertions.assertArrayEquals(new int[]{0, 500, 1030, 1560}, a);
                Assertions.assertArrayEquals(new int[]{0, 530, 1060, 1560}, d);
            }
        }


        Assertions.assertEquals(checksum, network.checksum());
    }

    @AfterEach
    public void tearDown () {
        this.network = null;
    }
}
