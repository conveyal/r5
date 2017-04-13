package com.conveyal.r5.streets;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by mabu on 29.3.2017.
 */
public class TurnSplitTest {
    private static final Logger LOG = LoggerFactory.getLogger(TurnSplitTest.class);

    private static StreetLayer sl;

    @Before
    public void setUpGraph() throws Exception {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("turn-restriction-split-test.pbf").toString());

        sl = new StreetLayer(TNBuilderConfig.defaultConfig());
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

    }

    //Test if turn restriction still works if fromEdge is split
    //This splitting happens in StreetLayer#getOrCreateVertexNear which is used in associateBikeSharing, buildParkAndRideNodes and associateStops
    @Test
    public void testTurnRestrictionWithSplitOnFrom() throws Exception {

        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.fromLat = 38.8930088;
        profileRequest.fromLon = -76.998343;
        profileRequest.toLat = 38.8914185;
        profileRequest.toLon = -76.9962294;

        StreetRouter streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;


        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);

        int oldWeight = lastState.weight;
        StreetRouter.State state = lastState;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        TurnRestriction tr = sl.turnRestrictions.get(1);

        LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 145 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

        //Splits "from Turn restriction edge"
        int vertex = sl.createAndLinkVertex(38.8921836,-76.9959183);

        sl.buildEdgeLists();

        streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;



        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);
        state = lastState;

        int newWeight = lastState.weight;

        assertEquals(oldWeight, newWeight);


        //LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            //LOG.info("V:{} W:{} be:{}, flags:{}", state.vertex, state.weight, state.backEdge, edge.getFlagsAsString());
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 146 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

    }

    //Test if turn restriction still works if fromEdge is split which isn't forward edge but backward edge
    //This splitting happens in StreetLayer#getOrCreateVertexNear which is used in associateBikeSharing, buildParkAndRideNodes and associateStops
    @Test
    public void testTurnRestrictionWithSplitOnFromBackwardEdge() throws Exception {

        TurnRestriction restriction = new TurnRestriction();
        restriction.fromEdge = 45;
        restriction.toEdge = 28;
        restriction.only = false;
        int ridx = sl.turnRestrictions.size();
        sl.turnRestrictions.add(restriction);
        sl.edgeStore.turnRestrictions.put(restriction.fromEdge, ridx);
        sl.addReverseTurnRestriction(restriction, ridx);


        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.fromLat = 38.89098;
        profileRequest.fromLon = -76.99478;
        profileRequest.toLat = 38.891657;
        profileRequest.toLon = -76.99661326;

        StreetRouter streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;


        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);

        int oldWeight = lastState.weight;
        StreetRouter.State state = lastState;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        TurnRestriction tr = sl.turnRestrictions.get(2);

        LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getLengthMm());
            //Turn from 45 to 28 is in Turn restriction 153 is new splitted edge
            assertFalse(state.backEdge == 28 && (state.backState.backEdge == 45 || state.backState.backEdge == 153));
            state = state.backState;

        }

        //Splits "from Turn restriction edge"
        int vertex = sl.createAndLinkVertex(38.8909806,-76.995403);

        sl.buildEdgeLists();

        streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;



        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);
        state = lastState;

        int newWeight = lastState.weight;

        //Weight isn't exactly the same because edge is split and some decimals are forgotten
        // when weight is summed since it's an integer not float.
        assertEquals(oldWeight, newWeight+1);


        //LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            //LOG.info("V:{} W:{} be:{}, flags:{}", state.vertex, state.weight, state.backEdge, edge.getFlagsAsString());
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getLengthMm());
            //Turn from 45 to 28 is in Turn restriction 153 is new splitted edge
            assertFalse(state.backEdge == 28 && (state.backState.backEdge == 45 || state.backState.backEdge == 153));
            state = state.backState;

        }

    }

    //Test if turn restriction still works if toEdge is split
    //This splitting happens in StreetLayer#getOrCreateVertexNear which is used in associateBikeSharing, buildParkAndRideNodes and associateStops
    @Test
    public void testTurnRestrictionWithSplitOnTo() throws Exception {

        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.fromLat = 38.8930088;
        profileRequest.fromLon = -76.998343;
        profileRequest.toLat = 38.8914185;
        profileRequest.toLon = -76.9962294;

        StreetRouter streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;


        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);

        int oldWeight = lastState.weight;
        StreetRouter.State state = lastState;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        TurnRestriction tr = sl.turnRestrictions.get(1);

        LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 145 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

        //Splits "to Turn restriction edge"
        int vertex = sl.createAndLinkVertex(38.8919775,-76.996019);

        sl.buildEdgeLists();

        streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;



        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);
        state = lastState;

        int newWeight = lastState.weight;

        assertEquals(oldWeight, newWeight);


        //LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.info("V:{} W:{} be:{}, flags:{}", state.vertex, state.weight, state.backEdge, edge.getFlagsAsString());
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 146 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

    }

    //Test if turn restriction still works if toEdge is split which isn't forward edge but backward edge
    //This splitting happens in StreetLayer#getOrCreateVertexNear which is used in associateBikeSharing, buildParkAndRideNodes and associateStops
    @Test
    public void testTurnRestrictionWithSplitOnToBackwardEdge() throws Exception {

        TurnRestriction restriction = new TurnRestriction();
        restriction.fromEdge = 47;
        restriction.toEdge = 45;
        restriction.only = false;
        int ridx = sl.turnRestrictions.size();
        sl.turnRestrictions.add(restriction);
        sl.edgeStore.turnRestrictions.put(restriction.fromEdge, ridx);
        sl.addReverseTurnRestriction(restriction, ridx);


        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.fromLat = 38.89098;
        profileRequest.fromLon = -76.99478;
        profileRequest.toLat = 38.891657;
        profileRequest.toLon = -76.99661326;

        StreetRouter streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;


        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);

        int oldWeight = lastState.weight;
        StreetRouter.State state = lastState;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        TurnRestriction tr = sl.turnRestrictions.get(2);

        LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getLengthMm());
            //Turn from 47 to 45 is in Turn restriction 153 is new splitted edge
            assertFalse(state.backEdge == 45 && (state.backState.backEdge == 47 || state.backState.backEdge == 153));
            state = state.backState;

        }

        //Splits "from Turn restriction edge"
        int vertex = sl.createAndLinkVertex(38.8909806,-76.995403);

        sl.buildEdgeLists();

        streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;



        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);
        state = lastState;

        int newWeight = lastState.weight;

        //Weight isn't exactly the same because edge is split and some decimals are forgotten
        // when weight is summed since it's an integer not float.
        assertEquals(oldWeight, newWeight);


        LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            //LOG.info("V:{} W:{} be:{}, flags:{}", state.vertex, state.weight, state.backEdge, edge.getFlagsAsString());
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getLengthMm());
            //Turn from 47 to 45 is in Turn restriction 153 is new splitted edge
            assertFalse(state.backEdge == 45 && (state.backState.backEdge == 47 || state.backState.backEdge == 153));
            state = state.backState;

        }

    }


    //Test if turn restriction still works if viaEdge is split
    //This splitting happens in StreetLayer#getOrCreateVertexNear which is used in associateBikeSharing, buildParkAndRideNodes and associateStops
    @Test
    public void testTurnRestrictionWithSplitOnVia() throws Exception {

        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.fromLat = 38.8930088;
        profileRequest.fromLon = -76.998343;
        profileRequest.toLat = 38.8914185;
        profileRequest.toLon = -76.99933;

        //Changes turnRestriction from:146 to 134 to 146 via 134 to 136
        sl.turnRestrictions.get(1).toEdge = 136;
        sl.turnRestrictions.get(1).viaEdges = new int[]{ 134 };
        sl.edgeStore.turnRestrictionsVia.put(134, 1);

        StreetRouter streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;


        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);

        int oldWeight = lastState.weight;
        StreetRouter.State state = lastState;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        TurnRestriction tr = sl.turnRestrictions.get(1);

        LOG.debug("TR:{}->{}->{}", tr.fromEdge, tr.viaEdges, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 145 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

        //Splits "via Turn restriction edge 134"
        int vertex = sl.createAndLinkVertex(38.8919775,-76.996019);

        sl.buildEdgeLists();

        streetRouter = new StreetRouter(sl);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.CAR;



        streetRouter.distanceLimitMeters = 5_000;
        //Split for end coordinate
        assertTrue("Destination must be found", streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon));
        assertTrue("Origin must be found", streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon));

        streetRouter.route();

        lastState = streetRouter.getState(streetRouter.getDestinationSplit());
        //LOG.info("W:{}", lastState.weight);
        state = lastState;

        int newWeight = lastState.weight;


        //LOG.info("TR:{}->{}", tr.fromEdge, tr.toEdge);
        while (state != null) {
            edge.seek(state.backEdge);
            LOG.debug("V:{} W:{} be:{}, TR:{} flags:{}", state.vertex, state.weight, state.backEdge, sl.edgeStore.turnRestrictions.containsKey(state.backEdge),  edge.getFlagsAsString());
            //Turn from 143 to 146 is in Turn restriction
            assertFalse(state.backEdge == 134 && (state.backState.backEdge == 152 || state.backState.backEdge == 146));
            state = state.backState;

        }

        assertEquals(oldWeight, newWeight);

    }
}
