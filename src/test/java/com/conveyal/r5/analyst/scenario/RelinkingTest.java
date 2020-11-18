package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Route;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;

/**
 * Test that when a modification creates new stops and therefore new street vertices and edges,
 * the transfers, stop trees, and PointSet linkages are updated accordingly. Not only for the newly created stops,
 * but for other stops within walking distance of them.
 *
 * R5 has an optimization that updates only those locations that may have actually changed as a result
 * of the modification, rather than updating all locations.
 */
public class RelinkingTest {

    public TransportNetwork network;
    public long checksum;

    @BeforeEach
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

    /**
     * Add a bidirectional pattern with one frequency entry that is perpendicular to the existing route.
     * It has three stops, all of which are newly created.
     * The second of these three stops is near stops s4 and s5 on the existing route.
     */
    @Test
    public void testAddBidirectionalTrip () {

        Assertions.assertEquals(1, network.transitLayer.tripPatterns.size());

        AddTrips at = new AddTrips();
        at.bidirectional = true;
        at.stops = Arrays.asList(
                new StopSpec(-83.0345, 39.962),
                new StopSpec(-83.0014, 39.962),
                new StopSpec(-82.9495, 39.962)
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
        Assertions.assertEquals(3, mod.transitLayer.tripPatterns.size());
        Assertions.assertEquals(5, network.transitLayer.getStopCount());
        Assertions.assertEquals(8, mod.transitLayer.getStopCount());

        // Check that stop s4 is in the transfers for stop 6
        // (the middle stop of the three new ones at indexes 5, 6, 7)
        // Unfortunately new stops don't have string IDs so we have to couple this strongly to the implementation
        // and assume that these new stops will be added in the order they are specified in the modification.
        TIntSet foundStops = new TIntHashSet();
        int[] transfers = mod.transitLayer.transfersForStop.get(6).toArray();
        for (int i = 0; i < transfers.length; i += 2) {
            int stop = transfers[i];
            int distance = transfers[i+1];
            foundStops.add(stop);
            Assertions.assertTrue(distance >= 0);
        }
        int s4StopIndex = mod.transitLayer.indexForStopId.get("SINGLE_LINE:s4");
        Assertions.assertTrue(foundStops.contains(s4StopIndex));

        // Check that stop stop 6 (the middle stop of the three new ones at indexes 5, 6, 7)
        // is in the transfers for existing stop s4
        transfers = mod.transitLayer.transfersForStop.get(s4StopIndex).toArray();
        foundStops.clear();
        for (int i = 0; i < transfers.length; i += 2) {
            int stop = transfers[i];
            int distance = transfers[i+1];
            foundStops.add(stop);
            Assertions.assertTrue(distance >= 0);
        }
        Assertions.assertTrue(foundStops.contains(6));

        // Check that stops s3 and s4 are included in the distance table
        // for stop 6 (the middle stop of the three new ones at indexes 5, 6, 7)
        TIntIntMap distanceTable = mod.transitLayer.stopToVertexDistanceTables.get(6);
        Assertions.assertNotNull(distanceTable);
        int s4streetVertexIndex = mod.transitLayer.streetVertexForStop.get(s4StopIndex);
        Assertions.assertTrue(distanceTable.containsKey(s4streetVertexIndex));
        int s3StopIndex = mod.transitLayer.indexForStopId.get("SINGLE_LINE:s3");
        int s3streetVertexIndex = mod.transitLayer.streetVertexForStop.get(s3StopIndex);
        Assertions.assertTrue(distanceTable.containsKey(s3streetVertexIndex));

        // Check that stop 6 (the middle stop of the three new ones at indexes 5, 6, 7)
        // is included in the distance table for stops s3 and s4
        int newStopStreetVertex = mod.transitLayer.streetVertexForStop.get(6);
        Assertions.assertTrue(newStopStreetVertex > 2000);
        distanceTable = mod.transitLayer.stopToVertexDistanceTables.get(s3StopIndex);
        Assertions.assertNotNull(distanceTable);
        Assertions.assertTrue(distanceTable.containsKey(newStopStreetVertex));
        distanceTable = mod.transitLayer.stopToVertexDistanceTables.get(s4StopIndex);
        Assertions.assertNotNull(distanceTable);
        Assertions.assertTrue(distanceTable.containsKey(newStopStreetVertex));

        // TODO check that PointSets are properly relinked to the new street layer.

    }

}
