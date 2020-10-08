package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    /** Test that schedules on frequency-converted lines outside of frequency-conversion window are retained */
    @Test
    public void testScheduleRetention () {
        AdjustFrequency af = new AdjustFrequency();
        af.retainTripsOutsideFrequencyEntries = true;
        af.route = "MULTIPLE_PATTERNS:route";

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.startTime = 12 * 3600;
        entry.endTime = 14 * 3600;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip

        AddTrips.PatternTimetable entry2 = new AddTrips.PatternTimetable();
        entry2.headwaySecs = 900;
        entry2.startTime = 9 * 3600;
        entry2.endTime = 10 * 3600;
        entry2.monday = entry2.tuesday = entry2.wednesday = entry2.thursday = entry2.friday = entry2.saturday = entry2.sunday = true;
        entry2.sourceTrip = "MULTIPLE_PATTERNS:trip25200";

        af.entries = Arrays.asList(entry, entry2);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(af);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // there should be frequencies and schedules
        assertTrue(mod.transitLayer.hasFrequencies);
        assertTrue(mod.transitLayer.hasSchedules);

        // make sure we have the correct number of patterns and frequency entries
        int twoStopPatternCount = 0;
        int threeStopPatternCount = 0;
        int frequencyScheduleCount = 0;

        for (TripPattern pattern : mod.transitLayer.tripPatterns) {
            if (pattern.stops.length == 2) {
                twoStopPatternCount++;

                // this is the frequency converted pattern
                TripSchedule[] freqSchedules = pattern.tripSchedules.stream()
                        .filter(ts -> ts.headwaySeconds != null)
                        .toArray(i -> new TripSchedule[i]);

                // for entry and entry2
                TripSchedule ts, ts2;

                // order of the frequency networks is not important, so don't inadvertently test that.
                if (freqSchedules[0].startTimes[0] == entry.startTime) {
                    ts = freqSchedules[0];
                    ts2 = freqSchedules[1];
                } else {
                    ts2 = freqSchedules[0];
                    ts = freqSchedules[1];
                }

                assertArrayEquals(new int[] { entry.startTime }, ts.startTimes);
                assertArrayEquals(new int[] { entry.endTime }, ts.endTimes);
                assertArrayEquals(new int[] { entry.headwaySecs }, ts.headwaySeconds);

                assertArrayEquals(new int[] { entry2.startTime }, ts2.startTimes);
                assertArrayEquals(new int[] { entry2.endTime }, ts2.endTimes);
                assertArrayEquals(new int[] { entry2.headwaySecs }, ts2.headwaySeconds);

                frequencyScheduleCount += freqSchedules.length;
            } else {
                threeStopPatternCount++;

                // if it doesn't have two stops it should have three
                assertEquals(3, pattern.stops.length);
                // should all be schedule based
                assertTrue(pattern.tripSchedules.stream().allMatch(ts -> ts.headwaySeconds == null));

                boolean foundTripBefore9 = false;
                boolean foundTripAfter10 = false;
                boolean foundTripBetween12And2 = false;
                boolean foundTripAfter2 = false;

                for (TripSchedule schedule : pattern.tripSchedules) {
                    // should be no trips between 9 and 10
                    int dep = schedule.departures[0];
                    assertFalse(dep > 9 * 3600 && dep < 10 * 3600);

                    if (dep < 9 * 3600) foundTripBefore9 = true;
                    if (dep > 10 * 3600 && dep < 12 * 3600) foundTripAfter10 = true;

                    // the period in which service is dropped on some days
                    if (dep > 12 * 3600 && dep < 14 * 3600) {
                        foundTripBetween12And2 = true;

                        Service service = mod.transitLayer.services.get(schedule.serviceCode);
                        assertEquals(0, service.calendar.monday);
                        assertEquals(0, service.calendar.tuesday);
                        assertEquals(0, service.calendar.wednesday);
                        assertEquals(0, service.calendar.thursday);
                        assertEquals(0, service.calendar.friday);

                        // weekend service should be retained as frequency entry is not active on the weekends
                        assertEquals(1, service.calendar.saturday);
                        assertEquals(1, service.calendar.sunday);
                    } else {
                        // ensure we didn't mess up the service for trips at other times
                        Service service = mod.transitLayer.services.get(schedule.serviceCode);
                        assertEquals(1, service.calendar.monday);
                        assertEquals(1, service.calendar.tuesday);
                        assertEquals(1, service.calendar.wednesday);
                        assertEquals(1, service.calendar.thursday);
                        assertEquals(1, service.calendar.friday);
                        assertEquals(1, service.calendar.saturday);
                        assertEquals(1, service.calendar.sunday);
                    }

                    if (dep > 14 * 3600) foundTripAfter2 = true;
                }

                assertTrue(foundTripBefore9);
                assertTrue(foundTripAfter10);
                assertTrue(foundTripBetween12And2);
                assertTrue(foundTripAfter2);
            }
        }

        assertEquals(1, threeStopPatternCount);
        assertEquals(1, twoStopPatternCount);
        assertEquals(2, frequencyScheduleCount);
    }

    /** Test adding scheduled trips */
    @Test
    public void testAddedScheduledTrips () {
        AdjustFrequency af = new AdjustFrequency();
        af.route = "MULTIPLE_PATTERNS:route";
        af.retainTripsOutsideFrequencyEntries = true;
        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.firstDepartures = new int[] { 25300, 25400 };
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip

        af.entries = Collections.singletonList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Collections.singletonList(af);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        TripPattern originalTwoStopPattern = network.transitLayer.tripPatterns.stream()
                .filter(p -> p.stops.length == 2)
                .findFirst()
                .orElse(null);

        TripPattern originalThreeStopPattern = network.transitLayer.tripPatterns.stream()
                .filter(p -> p.stops.length == 3)
                .findFirst()
                .orElse(null);

        TripPattern newTwoStopPattern = mod.transitLayer.tripPatterns.stream()
                .filter(p -> p.stops.length == 2)
                .findFirst()
                .orElse(null);

        TripPattern newThreeStopPattern = mod.transitLayer.tripPatterns.stream()
                .filter(p -> p.stops.length == 3)
                .findFirst()
                .orElse(null);

        assertNotNull(originalTwoStopPattern);
        assertNotNull(originalThreeStopPattern);
        assertNotNull(newTwoStopPattern);
        assertNotNull(newThreeStopPattern);

        assertEquals(2, mod.transitLayer.tripPatterns.size());

        // we did nothing to three stop trips, and all should be retained
        assertEquals(originalThreeStopPattern.tripSchedules.size(), newThreeStopPattern.tripSchedules.size());
        // we have added two trip schedules
        assertEquals(originalTwoStopPattern.tripSchedules.size() + 2, newTwoStopPattern.tripSchedules.size());

        // make sure the schedules were added correctly
        // TripSchedules should be sorted by departure, so the relevant trips should be in positions 1 and 2
        TripSchedule added1 = newTwoStopPattern.tripSchedules.get(1);

        assertNull(added1.headwaySeconds);
        assertNull(added1.startTimes);
        assertNull(added1.endTimes);

        assertArrayEquals(new int[] { 25300, 25800 }, added1.arrivals);
        assertArrayEquals(new int[] { 25300, 25800 }, added1.departures);

        assertTrue(newTwoStopPattern.servicesActive.get(added1.serviceCode));

        TripSchedule added2 = newTwoStopPattern.tripSchedules.get(2);

        assertNull(added2.headwaySeconds);
        assertNull(added2.startTimes);
        assertNull(added2.endTimes);

        assertArrayEquals(new int[] { 25400, 25900 }, added2.arrivals);
        assertArrayEquals(new int[] { 25400, 25900 }, added2.departures);

        assertTrue(newTwoStopPattern.servicesActive.get(added2.serviceCode));

    }

    @Test
    public void testPhasing () {
        AdjustFrequency af = new AdjustFrequency();
        af.route = "MULTIPLE_PATTERNS:route";

        assertEquals(2, network.transitLayer.tripPatterns.size());

        network.transitLayer.tripPatterns.forEach(tp -> {
            assertTrue(tp.hasSchedules);
            assertFalse(tp.hasFrequencies);
        });

        // make an entry
        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 1800;
        entry.startTime = 6 * 3600;
        entry.endTime = 16 * 3600;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = entry.saturday = entry.sunday = true;
        entry.sourceTrip = "MULTIPLE_PATTERNS:trip25200"; // trip25200 is a two-stop trip
        entry.entryId = "SHORT_TURN";

        AddTrips.PatternTimetable entry2 = new AddTrips.PatternTimetable();
        entry2.headwaySecs = 1800;
        entry2.startTime = 16 * 3600;
        entry2.endTime = 22 * 3600;
        entry2.monday = entry2.tuesday = entry2.wednesday = entry2.thursday = entry2.friday = entry2.saturday = entry2.sunday = true;
        entry2.sourceTrip = "MULTIPLE_PATTERNS:trip25800"; // trip25800 is a three-stop trip
        entry2.phaseFromTimetable = "SHORT_TURN";
        entry2.phaseFromStop = "MULTIPLE_PATTERNS:s1";
        entry2.phaseAtStop = "MULTIPLE_PATTERNS:s2";
        entry2.phaseSeconds = 900;

        af.entries = Arrays.asList(entry, entry2);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(af);
        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        TripSchedule ts1 = mod.transitLayer.tripPatterns.stream()
                .flatMap(tp -> tp.tripSchedules.stream())
                .filter(ts -> ts.tripId.equals("MULTIPLE_PATTERNS:trip25200"))
                .findFirst()
                .orElse(null);

        assertNotNull(ts1);

        TripSchedule ts2 = mod.transitLayer.tripPatterns.stream()
                .flatMap(tp -> tp.tripSchedules.stream())
                .filter(ts -> ts.tripId.equals("MULTIPLE_PATTERNS:trip25800"))
                .findFirst()
                .orElse(null);

        assertNotNull(ts2);

        assertArrayEquals(ts1.frequencyEntryIds, ts2.phaseFromId);
        assertArrayEquals(new String[] { "MULTIPLE_PATTERNS:s1" }, ts2.phaseFromStop);
        assertArrayEquals(new String[] { "MULTIPLE_PATTERNS:s2" }, ts2.phaseAtStop);
        assertArrayEquals(new int[] { 900 }, ts2.phaseSeconds);

        assertNull(ts1.phaseFromId);
        assertNull(ts1.phaseAtStop);
        assertNull(ts1.phaseFromStop);
        assertNull(ts1.phaseSeconds);

        assertEquals(checksum, network.checksum());
    }

    @After
    public void tearDown () {
        network = null;
    }
}
