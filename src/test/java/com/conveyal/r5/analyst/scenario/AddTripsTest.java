package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test adding trips.
 */
public class AddTripsTest {
    public TransportNetwork network;
    public long checksum;

    @Before
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

    /** simple test of adding a unidirectional trip with one frequency entry and no added stops */
    @Test
    public void testAddUnidirectionalTrip () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = false;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(at);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(2, mod.transitLayer.tripPatterns.size());

        // find the added trip pattern
        TripPattern pattern = mod.transitLayer.tripPatterns.stream()
                .filter(pat -> pat.tripSchedules.get(0).headwaySeconds != null)
                .findFirst()
                .orElse(null);

        // was it added?
        assertNotNull(pattern);

        // make sure the stops are in the right order
        assertEquals(3, pattern.stops.length);
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(pattern.stops[0]));
        assertEquals("SINGLE_LINE:s2", mod.transitLayer.stopIdForIndex.get(pattern.stops[1]));
        assertEquals("SINGLE_LINE:s3", mod.transitLayer.stopIdForIndex.get(pattern.stops[2]));

        // check the timetable
        assertEquals(1, pattern.tripSchedules.size());
        TripSchedule ts = pattern.tripSchedules.get(0);
        assertEquals(3, ts.departures.length);
        assertEquals(3, ts.arrivals.length);
        assertEquals(0, ts.arrivals[0]);
        assertEquals(0, ts.departures[0]);
        assertEquals(120, ts.arrivals[1]);
        assertEquals(150, ts.departures[1]);
        assertEquals(290, ts.arrivals[2]);
        assertEquals(290, ts.departures[2]);

        // check the frequency
        assertArrayEquals(new int[] { entry.headwaySecs }, ts.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, ts.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, ts.endTimes);

        // check the calendar
        Service service0 = mod.transitLayer.services.get(ts.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        assertEquals(checksum, network.checksum());
    }

    /** simple test of adding a bidirectional trip with one frequency entry and no added stops */
    @Test
    public void testAddBidirectionalTrip () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = true;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(at);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(3, mod.transitLayer.tripPatterns.size());

        // find the added trip patterns
        List<TripPattern> patterns = mod.transitLayer.tripPatterns.stream()
                .filter(pat -> pat.tripSchedules.get(0).headwaySeconds != null)
                .collect(Collectors.toList());

        assertEquals(2, patterns.size());

        TripPattern pattern, backPattern;

        // sort out forward and back patterns
        if ("SINGLE_LINE:s1".equals(mod.transitLayer.stopIdForIndex.get(patterns.get(0).stops[0]))) {
            pattern = patterns.get(0);
            backPattern = patterns.get(1);
        } else {
            pattern = patterns.get(1);
            backPattern = patterns.get(0);
        }

        // make sure the stops are in the right order
        assertEquals(3, pattern.stops.length);
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(pattern.stops[0]));
        assertEquals("SINGLE_LINE:s2", mod.transitLayer.stopIdForIndex.get(pattern.stops[1]));
        assertEquals("SINGLE_LINE:s3", mod.transitLayer.stopIdForIndex.get(pattern.stops[2]));

        // check the timetable
        assertEquals(1, pattern.tripSchedules.size());
        TripSchedule ts = pattern.tripSchedules.get(0);
        assertEquals(3, ts.departures.length);
        assertEquals(3, ts.arrivals.length);
        assertEquals(0, ts.arrivals[0]);
        assertEquals(0, ts.departures[0]);
        assertEquals(120, ts.arrivals[1]);
        assertEquals(150, ts.departures[1]);
        assertEquals(290, ts.arrivals[2]);
        assertEquals(290, ts.departures[2]);

        // check the frequency
        assertArrayEquals(new int[] { entry.headwaySecs }, ts.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, ts.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, ts.endTimes);

        // check the calendar
        Service service0 = mod.transitLayer.services.get(ts.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        // now do it all backwards
        // make sure the stops are in the right order
        assertEquals(3, backPattern.stops.length);
        assertEquals("SINGLE_LINE:s3", mod.transitLayer.stopIdForIndex.get(backPattern.stops[0]));
        assertEquals("SINGLE_LINE:s2", mod.transitLayer.stopIdForIndex.get(backPattern.stops[1]));
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(backPattern.stops[2]));

        // check the timetable
        assertEquals(1, backPattern.tripSchedules.size());
        ts = backPattern.tripSchedules.get(0);
        assertEquals(3, ts.departures.length);
        assertEquals(3, ts.arrivals.length);
        assertEquals(0, ts.arrivals[0]);
        assertEquals(0, ts.departures[0]);
        assertEquals(140, ts.arrivals[1]);
        assertEquals(170, ts.departures[1]);
        assertEquals(290, ts.arrivals[2]);
        assertEquals(290, ts.departures[2]);

        // check the frequency
        assertArrayEquals(new int[] { entry.headwaySecs }, ts.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, ts.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, ts.endTimes);

        // check the calendar
        service0 = mod.transitLayer.services.get(ts.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        assertEquals(checksum, network.checksum());
    }

    /**
     * Simple test of adding a unidirectional trip with one frequency entry and a newly created stop.
     */
    @Test
    public void testAddUnidirectionalTripWithAddedStops () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = false;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec(-83.001, 40.012),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(at);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(2, mod.transitLayer.tripPatterns.size());

        // find the added trip pattern
        TripPattern pattern = mod.transitLayer.tripPatterns.stream()
                .filter(pat -> pat.tripSchedules.get(0).headwaySeconds != null)
                .findFirst()
                .orElse(null);

        // was it added?
        assertNotNull(pattern);

        // make sure the stops are in the right order
        assertEquals(3, pattern.stops.length);
        assertEquals("SINGLE_LINE:s1", mod.transitLayer.stopIdForIndex.get(pattern.stops[0]));
        assertEquals("SINGLE_LINE:s3", mod.transitLayer.stopIdForIndex.get(pattern.stops[2]));

        int createdVertex = mod.transitLayer.streetVertexForStop.get(pattern.stops[1]);
        VertexStore.Vertex v = mod.streetLayer.vertexStore.getCursor(createdVertex);

        // make sure the stop is in the right place
        assertEquals(40.012, v.getLat(), 1e-6);
        assertEquals(-83.001, v.getLon(), 1e-6);

        // make sure it's linked to the street network
        StreetRouter r = new StreetRouter(mod.streetLayer);
        r.setOrigin(createdVertex);
        r.distanceLimitMeters = 500;
        // avoid a lot of spewing warnings about resource limiting
        r.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        r.route();

        assertTrue(r.getReachedVertices().size() > 5);

        // Make sure a distance table exists for this stop.
        TIntIntMap distanceTable = mod.transitLayer.stopToVertexDistanceTables.get(pattern.stops[1]);
        assertNotNull(distanceTable);
        assertFalse(distanceTable.isEmpty());

        // Make sure this stop has transfers.
        TIntList transfers = mod.transitLayer.transfersForStop.get(pattern.stops[1]);
        assertNotNull(transfers);
        // make sure that s2 is a target of a transfer
        boolean s2found = false;

        for (int i = 0; i < transfers.size(); i += 2) {
            if ("SINGLE_LINE:s2".equals(mod.transitLayer.stopIdForIndex.get(transfers.get(i)))) {
                s2found = true;
                break;
            }
        }

        assertTrue(s2found);

        // check the timetable
        assertEquals(1, pattern.tripSchedules.size());
        TripSchedule ts = pattern.tripSchedules.get(0);
        assertEquals(3, ts.departures.length);
        assertEquals(3, ts.arrivals.length);
        assertEquals(0, ts.arrivals[0]);
        assertEquals(0, ts.departures[0]);
        assertEquals(120, ts.arrivals[1]);
        assertEquals(150, ts.departures[1]);
        assertEquals(290, ts.arrivals[2]);
        assertEquals(290, ts.departures[2]);

        // check the frequency
        assertArrayEquals(new int[] { entry.headwaySecs }, ts.headwaySeconds);
        assertArrayEquals(new int[] { entry.startTime }, ts.startTimes);
        assertArrayEquals(new int[] { entry.endTime }, ts.endTimes);

        // check the calendar
        Service service0 = mod.transitLayer.services.get(ts.serviceCode);
        assertEquals(entry.monday, service0.calendar.monday == 1);
        assertEquals(entry.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry.thursday, service0.calendar.thursday == 1);
        assertEquals(entry.friday, service0.calendar.friday == 1);
        assertEquals(entry.saturday, service0.calendar.saturday == 1);
        assertEquals(entry.sunday, service0.calendar.sunday == 1);

        assertEquals(checksum, network.checksum());
    }

    @Test
    public void testMultipleFrequencyEntries () {
        AddTrips at = new AddTrips();
        at.bidirectional = false;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry0 = new AddTrips.PatternTimetable();
        entry0.headwaySecs = 1200;
        entry0.monday = entry0.tuesday = entry0.wednesday = entry0.thursday = entry0.friday = false;
        entry0.saturday = entry0.sunday = true;
        entry0.hopTimes = new int[] { 140, 160 };
        entry0.dwellTimes = new int[] { 0, 30, 0 };
        entry0.startTime = 11 * 3600;
        entry0.endTime = 16 * 3600;

        AddTrips.PatternTimetable entry1 = new AddTrips.PatternTimetable();
        entry1.headwaySecs = 900;
        entry1.monday = entry1.tuesday = entry1.wednesday = entry1.thursday = entry1.friday = true;
        entry1.saturday = entry1.sunday = false;
        entry1.hopTimes = new int[] { 120, 140 };
        entry1.dwellTimes = new int[] { 0, 30, 0 };
        entry1.startTime = 7 * 3600;
        entry1.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry0, entry1);

        Scenario scenario = new Scenario();
        scenario.modifications = Collections.singletonList(at);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        // original pattern plus added pattern
        assertEquals(2, mod.transitLayer.tripPatterns.size());

        TripPattern pattern = mod.transitLayer.tripPatterns.stream()
                .filter(tp -> !"SINGLE_LINE:route".equals(tp.routeId))
                .findFirst()
                .orElse(null);

        assertNotNull(pattern);

        // one from each entry
        assertEquals(2, pattern.tripSchedules.size());

        TripSchedule schedule0 = null, schedule1 = null;

        for (TripSchedule schedule : pattern.tripSchedules) {
            if (schedule.headwaySeconds[0] == entry0.headwaySecs) schedule0 = schedule;
            else schedule1 = schedule;
        }

        assertNotNull(schedule0);
        assertNotNull(schedule1);

        assertArrayEquals(new int[] { 0, 140, 330 }, schedule0.arrivals);
        assertArrayEquals(new int[] { 0, 170, 330 }, schedule0.departures);

        assertArrayEquals(new int[] { entry0.headwaySecs }, schedule0.headwaySeconds);
        assertArrayEquals(new int[] { entry0.startTime }, schedule0.startTimes);
        assertArrayEquals(new int[] { entry0.endTime }, schedule0.endTimes);

        Service service0 = mod.transitLayer.services.get(schedule0.serviceCode);
        assertEquals(entry0.monday, service0.calendar.monday == 1);
        assertEquals(entry0.tuesday, service0.calendar.tuesday == 1);
        assertEquals(entry0.wednesday, service0.calendar.wednesday == 1);
        assertEquals(entry0.thursday, service0.calendar.thursday == 1);
        assertEquals(entry0.friday, service0.calendar.friday == 1);
        assertEquals(entry0.saturday, service0.calendar.saturday == 1);
        assertEquals(entry0.sunday, service0.calendar.sunday == 1);

        assertArrayEquals(new int[] { 0, 120, 290 }, schedule1.arrivals);
        assertArrayEquals(new int[] { 0, 150, 290 }, schedule1.departures);

        assertArrayEquals(new int[] { entry1.headwaySecs }, schedule1.headwaySeconds);
        assertArrayEquals(new int[] { entry1.startTime }, schedule1.startTimes);
        assertArrayEquals(new int[] { entry1.endTime }, schedule1.endTimes);

        Service service1 = mod.transitLayer.services.get(schedule1.serviceCode);
        assertEquals(entry1.monday, service1.calendar.monday == 1);
        assertEquals(entry1.tuesday, service1.calendar.tuesday == 1);
        assertEquals(entry1.wednesday, service1.calendar.wednesday == 1);
        assertEquals(entry1.thursday, service1.calendar.thursday == 1);
        assertEquals(entry1.friday, service1.calendar.friday == 1);
        assertEquals(entry1.saturday, service1.calendar.saturday == 1);
        assertEquals(entry1.sunday, service1.calendar.sunday == 1);

        assertEquals(checksum, network.checksum());
    }

    /** simple test of adding two unidirectional trips with phasing */
    @Test
    public void testAddUnidirectionalTripWithPhasing () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = false;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;
        entry.entryId = "FREQUENCY_ENTRY_1";

        at.frequencies = Arrays.asList(entry);

        AddTrips at2 = new AddTrips();
        at2.bidirectional = false;
        at2.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at2.mode = Route.BUS;

        entry = new AddTrips.PatternTimetable();
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120 };
        entry.dwellTimes = new int[] { 0, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;
        entry.phaseFromTimetable = "FREQUENCY_ENTRY_1";
        entry.phaseFromStop = "SINGLE_LINE:s1";
        entry.phaseAtStop = "SINGLE_LINE:s2";
        entry.entryId = "FREQUENCY_ENTRY_2";
        entry.phaseSeconds = 300;

        at2.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(at, at2);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(3, mod.transitLayer.tripPatterns.size());

        // find the relevant trip schedules
        TripSchedule ts1 = mod.transitLayer.tripPatterns.stream()
                .flatMap(tp -> tp.tripSchedules.stream())
                .filter(ts -> ts.frequencyEntryIds != null && ts.frequencyEntryIds[0].equals("FREQUENCY_ENTRY_1"))
                .findFirst()
                .orElse(null);

        assertNotNull(ts1);

        TripSchedule ts2 = mod.transitLayer.tripPatterns.stream()
                .flatMap(tp -> tp.tripSchedules.stream())
                .filter(ts -> ts.frequencyEntryIds != null && ts.frequencyEntryIds[0].equals("FREQUENCY_ENTRY_2"))
                .findFirst()
                .orElse(null);

        assertNotNull(ts2);

        assertArrayEquals(ts1.frequencyEntryIds, ts2.phaseFromId);
        assertArrayEquals(new String[] { "SINGLE_LINE:s2" }, ts2.phaseAtStop);
        assertArrayEquals(new String[] { "SINGLE_LINE:s1" }, ts2.phaseFromStop);
        assertArrayEquals(new int[] { 300 }, ts2.phaseSeconds);

        assertNull(ts1.phaseFromId);
        assertNull(ts1.phaseAtStop);
        assertNull(ts1.phaseFromStop);
        assertNull(ts1.phaseSeconds);

        assertEquals(checksum, network.checksum());
    }

    /** Test that adding exact-times trips works */
    @Test
    public void testAddExactTimes () {
        assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = false;
        at.stops = Arrays.asList(
                new StopSpec("SINGLE_LINE:s1"),
                new StopSpec("SINGLE_LINE:s2"),
                new StopSpec("SINGLE_LINE:s3")
        );
        at.mode = Route.BUS;

        AddTrips.PatternTimetable entry = new AddTrips.PatternTimetable();
        entry.firstDepartures = new int[] { 1800, 3600, 5400, 7200 };
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario();
        scenario.modifications = Arrays.asList(at);

        TransportNetwork mod = scenario.applyToTransportNetwork(network);

        assertEquals(2, mod.transitLayer.tripPatterns.size());

        TripPattern pattern = mod.transitLayer.tripPatterns.get(1);

        // find all departure times, sort and make sure they match
        int[] foundDepartures = pattern.tripSchedules.stream()
                .mapToInt(s -> s.departures[0])
                .sorted()
                .toArray();

        assertArrayEquals(entry.firstDepartures, foundDepartures);

        // check that correct number of stops show up
        assertEquals(3, pattern.stops.length);

        // check trip length
        for (TripSchedule schedule : pattern.tripSchedules) {
            assertEquals(290, schedule.arrivals[2] - schedule.departures[0]);
            // make sure it's not a frequency trip
            assertNull(schedule.headwaySeconds);
        }
    }

    @After
    public void tearDown () {
        this.network = null;
    }
}
