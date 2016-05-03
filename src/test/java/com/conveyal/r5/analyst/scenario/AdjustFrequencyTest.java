package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;
import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;

/**
 * Test converting lines to a frequency representation.
 */
public class AdjustFrequencyTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.MULTIPLE_PATTERNS);
        checksum = network.checksum();
    }

    @Test
    public void testFrequencyConversion () {
        AdjustFrequency af = new AdjustFrequency();
        af.route = "MULTIPLE_PATTERNS:route";

        assertEquals(2, network.transitLayer.tripPatterns.size());

        network.transitLayer.tripPatterns.forEach(tp -> {
            assertTrue(tp.hasSchedules);
            assertFalse(tp.hasFrequencies);
        });

        // make an entry
        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.startTime = 6 * 3600;
        entry.endTime = 16 * 3600;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = entry.saturday = entry.sunday = true;
        entry.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip

        AddTrips.PatternTimetable entry2 = new AddTrips.PatternTimetable();
        entry2.headwaySecs = 1200;
        entry2.startTime = 16 * 3600;
        entry2.endTime = 22 * 3600;
        entry2.monday = entry2.tuesday = entry2.wednesday = entry2.thursday = entry2.friday = entry2.saturday = entry2.sunday = true;
        entry2.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip

        af.entries = Arrays.asList(entry, entry2);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(af);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // Trip patterns not referred to by entries should be wiped
        assertEquals(1, mod.transitLayer.tripPatterns.size());

        TripPattern pattern = mod.transitLayer.tripPatterns.get(0);

        assertTrue(pattern.hasFrequencies);
        assertFalse(pattern.hasSchedules);

        assertEquals(2, pattern.tripSchedules.size());

        TripSchedule exemplar0 = pattern.tripSchedules.get(0);
        TripSchedule exemplar1 = pattern.tripSchedules.get(1);

        // order is not guaranteed, sort them out
        if (exemplar0.headwaySeconds[0] == entry2.headwaySecs) {
            // switch them
            TripSchedule originalExemplar0 = exemplar0;
            exemplar0 = exemplar1;
            exemplar1 = originalExemplar0;
        }

        assertArrayEquals(new int[] { 0, 500 }, exemplar0.arrivals);
        assertArrayEquals(new int[] { 0, 500 }, exemplar0.departures);

        assertArrayEquals(new int[] { entry.headwaySecs }, exemplar0.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, exemplar0.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, exemplar0.endTimes);

        Service service0 = mod.transitLayer.services.get(exemplar0.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        // should be moved to have first arrival at 0
        assertArrayEquals(new int[] { 0, 500 }, exemplar1.arrivals);
        assertArrayEquals(new int[] { 0, 500 }, exemplar1.departures);

        assertArrayEquals(new int[] { entry2.headwaySecs }, exemplar1.headwaySeconds);
        assertArrayEquals(new int[] { entry2.startTime }, exemplar1.startTimes);
        assertArrayEquals(new int[] { entry2.endTime }, exemplar1.endTimes);

        Service service1 = mod.transitLayer.services.get(exemplar1.serviceCode);
        assertEquals(entry2.monday, service1.calendar.monday == 1);
        assertEquals(entry2.tuesday, service1.calendar.tuesday == 1);
        assertEquals(entry2.wednesday, service1.calendar.wednesday == 1);
        assertEquals(entry2.thursday, service1.calendar.thursday == 1);
        assertEquals(entry2.friday, service1.calendar.friday == 1);
        assertEquals(entry2.saturday, service1.calendar.saturday == 1);
        assertEquals(entry2.sunday, service1.calendar.sunday == 1);

        // make sure we didn't modify base network
        network.transitLayer.tripPatterns.forEach(tp -> {
            assertTrue(tp.hasSchedules);
            assertFalse(tp.hasFrequencies);
        });

        assertEquals(checksum, network.checksum());
    }

    /** Test converting multiple patterns of a route to frequency representations */
    @Test
    public void testConvertingMultiplePatternsToFrequency () {
        AdjustFrequency af = new AdjustFrequency();
        af.route = "MULTIPLE_PATTERNS:route";

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.startTime = 6 * 3600;
        entry.endTime = 16 * 3600;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = entry.saturday = entry.sunday = true;
        entry.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip

        AddTrips.PatternTimetable entry2 = new AddTrips.PatternTimetable();
        entry2.headwaySecs = 1200;
        entry2.startTime = 16 * 3600;
        entry2.endTime = 22 * 3600;
        entry2.monday = entry2.tuesday = entry2.wednesday = entry2.thursday = entry2.friday = entry2.saturday = entry2.sunday = true;
        entry2.sourceTrip = "MULTIPLE_PATTERNS:trip25800"; // trip25800 is a three-stop trip

        af.entries = Arrays.asList(entry, entry2);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(af);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // both trip patterns should be still present
        assertEquals(2, mod.transitLayer.tripPatterns.size());

        TripPattern twoStop = null, threeStop = null;

        for (TripPattern tp : mod.transitLayer.tripPatterns) {
            if (tp.stops.length == 2) twoStop = tp;
            else if (tp.stops.length == 3) threeStop = tp;
        }

        assertNotNull(twoStop);
        assertNotNull(threeStop);

        assertTrue(twoStop.hasFrequencies);
        assertFalse(twoStop.hasSchedules);
        assertTrue(threeStop.hasFrequencies);
        assertFalse(threeStop.hasSchedules);

        assertEquals(1, twoStop.tripSchedules.size());
        assertEquals(1, threeStop.tripSchedules.size());

        TripSchedule exemplar0 = twoStop.tripSchedules.get(0);
        TripSchedule exemplar1 = threeStop.tripSchedules.get(0);

        assertArrayEquals(new int[] { 0, 500 }, exemplar0.arrivals);
        assertArrayEquals(new int[] { 0, 500 }, exemplar0.departures);

        assertArrayEquals(new int[] { entry.headwaySecs }, exemplar0.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, exemplar0.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, exemplar0.endTimes);

        Service service0 = mod.transitLayer.services.get(exemplar0.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        assertArrayEquals(new int[] { 0, 500, 1030 }, exemplar1.arrivals);
        assertArrayEquals(new int[] { 0, 530, 1030 }, exemplar1.departures);

        assertArrayEquals(new int[] { entry2.headwaySecs }, exemplar1.headwaySeconds);
        assertArrayEquals(new int[] { entry2.startTime }, exemplar1.startTimes);
        assertArrayEquals(new int[] { entry2.endTime }, exemplar1.endTimes);

        Service service1 = mod.transitLayer.services.get(exemplar1.serviceCode);
        assertEquals(entry2.monday, service1.calendar.monday == 1);
        assertEquals(entry2.tuesday, service1.calendar.tuesday == 1);
        assertEquals(entry2.wednesday, service1.calendar.wednesday == 1);
        assertEquals(entry2.thursday, service1.calendar.thursday == 1);
        assertEquals(entry2.friday, service1.calendar.friday == 1);
        assertEquals(entry2.saturday, service1.calendar.saturday == 1);
        assertEquals(entry2.sunday, service1.calendar.sunday == 1);

        // make sure we didn't modify base network
        network.transitLayer.tripPatterns.forEach(tp -> {
            assertTrue(tp.hasSchedules);
            assertFalse(tp.hasFrequencies);
        });

        assertEquals(checksum, network.checksum());
    }

    @After
    public void tearDown () {
        network = null;
    }
}
