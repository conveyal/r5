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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        entry.frequency = true;
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario(42);
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
        entry.frequency = true;
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario(42);
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

    /** simple test of adding a unidirectional trip with one frequency entry and no added stops */
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
        entry.frequency = true;
        entry.headwaySecs = 900;
        entry.monday = entry.tuesday = entry.wednesday = entry.thursday = entry.friday = true;
        entry.saturday = entry.sunday = false;
        entry.hopTimes = new int[] { 120, 140 };
        entry.dwellTimes = new int[] { 0, 30, 0 };
        entry.startTime = 7 * 3600;
        entry.endTime = 10 * 3600;

        at.frequencies = Arrays.asList(entry);

        Scenario scenario = new Scenario(42);
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
        r.distanceLimitMeters = 500;
        r.route();

        assertTrue(r.getReachedVertices().size() > 5);

        // make sure it has a stop tree
        TIntIntMap stopTree = mod.transitLayer.stopTrees.get(pattern.stops[1]);
        assertNotNull(stopTree);
        assertFalse(stopTree.isEmpty());

        // make sure it has transfers
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

    @After
    public void tearDown () {
        this.network = null;
    }
}
