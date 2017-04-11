package com.conveyal.r5.streets;

import com.conveyal.gtfs.model.Route;
import com.conveyal.r5.analyst.scenario.AddTrips;
import com.conveyal.r5.analyst.scenario.FakeGraph;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.analyst.scenario.StopSpec;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static org.junit.Assert.*;

/**
 * Tests that turn restriction works after scenario splits from/to/via edges
 *
 * Works means that turn restriction on original network stay the same (turnRestriction field in edgeStore and streetLayer) and
 * turnRestrictionsVia, turnRestrictionsReverse in Edgestore.
 *
 * And that in network with scenario they are updated.
 * Created by mabu on 11.4.2017.
 */
public class ScenarioModifyTurnRestrictions {
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioModifyTurnRestrictions.class);

    private TransportNetwork network;
    private long checksum;

    @Before
    public void setUpGraph() throws Exception {
        TNBuilderConfig tnBuilderConfig = new TNBuilderConfig(false);
        network = buildNetwork(StreetLayerTest.class.getResourceAsStream("turn-restriction-split-test.pbf"),
            "turn-restriction-split-test", tnBuilderConfig,
            FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();

    }

    //Creates Scenario with trip on specified stop and applies it to network
    //Returns modified network
    private TransportNetwork addStop(double lon, double lat) {
        AddTrips at = new AddTrips();
        at.bidirectional = true;
        at.stops = Arrays.asList(
            new StopSpec(lon, lat),
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

        return scenario.applyToTransportNetwork(network);
    }

    @Test
    public void testStopSplitsFromEdge() throws Exception {
        TurnRestriction tr = network.streetLayer.turnRestrictions.get(1);
        LOG.debug("TR:{}", tr);
        int turnRestrictionFrom = tr.fromEdge;
        int turnRestrictionSize = network.streetLayer.turnRestrictions.size();

        int[] restrictionsOnFrom = network.streetLayer.edgeStore.turnRestrictions
            .get(turnRestrictionFrom).toArray();

        //Stop was added that splits fromEdge on turn Restriction
        TransportNetwork mod = addStop(-76.9959183, 38.8921836);

        //Size of turn restrictions needs to be the same

        int copyTurnRestrictionSize = mod.streetLayer.turnRestrictions.size();

        //List of turn restrictions needs to be copied since if we add transitStops that split turnRestriction edges
        //We need to update turnRestrictions (since edges are different) and this needs to be different than original TurnRestriction list
        assertEquals(turnRestrictionSize, copyTurnRestrictionSize);

        //Original turnRestriction needs to remain the same
        assertEquals(turnRestrictionFrom, network.streetLayer.turnRestrictions.get(1).fromEdge);

        assertArrayEquals(restrictionsOnFrom, network.streetLayer.edgeStore.turnRestrictions.get(turnRestrictionFrom).toArray());

        //copied streetLayer needs to have new from edge
        assertNotEquals(turnRestrictionFrom, mod.streetLayer.turnRestrictions.get(1).fromEdge);

        assertNotEquals(restrictionsOnFrom, mod.streetLayer.edgeStore.turnRestrictions.get(turnRestrictionFrom).toArray());
    }

    @Test
    public void testStopSplitsToEdge() throws Exception {
        TurnRestriction tr = network.streetLayer.turnRestrictions.get(1);
        LOG.info("TR:{}", tr);
        int turnRestrictionTo = tr.toEdge;
        int turnRestrictionSize = network.streetLayer.turnRestrictions.size();

        int[] restrictionsOnTo = network.streetLayer.edgeStore.turnRestrictionsReverse
            .get(turnRestrictionTo).toArray();

        //Stop was added that splits toEdge on turn Restriction
        TransportNetwork mod = addStop(-76.996019,38.8919775);

        //Size of turn restrictions needs to be the same

        int copyTurnRestrictionSize = mod.streetLayer.turnRestrictions.size();

        //List of turn restrictions needs to be copied since if we add transitStops that split turnRestriction edges
        //We need to update turnRestrictions (since edges are different) and this needs to be different than original TurnRestriction list
        assertEquals(turnRestrictionSize, copyTurnRestrictionSize);

        //Original turnRestriction needs to remain the same
        assertEquals(turnRestrictionTo, network.streetLayer.turnRestrictions.get(1).toEdge);

        assertArrayEquals(restrictionsOnTo, network.streetLayer.edgeStore.turnRestrictionsReverse.get(turnRestrictionTo).toArray());

        //copied streetLayer needs to have same to edge
        assertEquals(turnRestrictionTo, mod.streetLayer.turnRestrictions.get(1).toEdge);

        assertArrayEquals(restrictionsOnTo, mod.streetLayer.edgeStore.turnRestrictionsReverse.get(turnRestrictionTo).toArray());
    }

    @Test
    public void testStopSplitsViaEdge() throws Exception {

        //Changes turnRestriction from:146 to 134 to 146 via 134 to 136
        network.streetLayer.turnRestrictions.get(1).toEdge = 136;
        network.streetLayer.turnRestrictions.get(1).viaEdges = new int[]{ 134 };
        network.streetLayer.edgeStore.turnRestrictionsVia.put(134, 1);

        TurnRestriction tr = network.streetLayer.turnRestrictions.get(1);
        LOG.info("TR:{}", tr);
        int turnRestrictionFrom = tr.fromEdge;
        int[] turnRestrictionsVia = tr.viaEdges;
        int turnRestrictionSize = network.streetLayer.turnRestrictions.size();

        int[] restrictionsOnVia = network.streetLayer.edgeStore.turnRestrictionsVia
            .get(turnRestrictionsVia[0]).toArray();

        //Stop was added that splits viaEdge on turn Restriction
        TransportNetwork mod = addStop(-76.996019, 38.8919775);

        //Size of turn restrictions needs to be the same

        int copyTurnRestrictionSize = mod.streetLayer.turnRestrictions.size();

        //List of turn restrictions needs to be copied since if we add transitStops that split turnRestriction edges
        //We need to update turnRestrictions (since edges are different) and this needs to be different than original TurnRestriction list
        assertEquals(turnRestrictionSize, copyTurnRestrictionSize);

        //Original turnRestriction needs to remain the same
        assertEquals(turnRestrictionFrom, network.streetLayer.turnRestrictions.get(1).fromEdge);

        assertArrayEquals(turnRestrictionsVia, network.streetLayer.turnRestrictions.get(1).viaEdges);

        assertArrayEquals(restrictionsOnVia, network.streetLayer.edgeStore.turnRestrictionsVia.get(turnRestrictionsVia[0]).toArray());
        assertFalse(network.streetLayer.edgeStore.turnRestrictionsVia.containsKey(154));

        //copied streetLayer needs to have new via edges
        assertEquals(turnRestrictionFrom, mod.streetLayer.turnRestrictions.get(1).fromEdge);

        assertEquals(turnRestrictionsVia.length+1, mod.streetLayer.turnRestrictions.get(1).viaEdges.length);

        assertArrayEquals(new int[]{1}, mod.streetLayer.edgeStore.turnRestrictionsVia.get(154).toArray());
    }
}
