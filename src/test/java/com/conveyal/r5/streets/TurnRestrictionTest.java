package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TurnRestrictionTest extends TurnTest {

    private static final Logger LOG = LoggerFactory.getLogger(TurnRestrictionTest.class);
    @Test
    public void testSimpleNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State s1 = r.getStateAtVertex(VW);
        LOG.debug("turn rest: {} {}", s1.dump(), s1.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(s1.weight > state.weight);
    }


    @Test
    public void testSimpleNoTurnReverse () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VW);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VS);
        LOG.debug("normal rev:{}", state.dump());
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.profileRequest.reverseSearch = true;
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VW);
        r.route();

        StreetRouter.State s1 = r.getStateAtVertex(VS);

        LOG.debug("turn rest rev:{}", s1.dump());

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(s1.weight > state.weight);
    }


    @Test
    public void testReverseTurnCosts() throws Exception {
        setUp(false);

        //Normal search
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);
        assertNotNull(state);




        //Same reverse search
        new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VW);
        r.route();

        StreetRouter.State reverseState = r.getStateAtVertex(VS);
        assertNotNull(reverseState);
        assertEquals(state.weight, reverseState.weight);

    }

    @Test
    public void testSimpleOnlyTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);
        assertNotNull(state);

        // must turn right from ES to EE
        restrictTurn(true, ES + 1, EE);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(VW);

        LOG.debug("Normal only turn:{}", restrictedState.compactDump(false));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);

        // The path should go through VE now because of the only turn restriction
        boolean foundVE = false;

        while (restrictedState != null) {
            if (restrictedState.vertex == VE) {
                foundVE = true;
                break;
            }

            restrictedState = restrictedState.backState;
        }

        assertTrue(foundVE);
    }

    @Test
    public void testTurnRestrictionRemaping() throws Exception {
        setUp(false);

        //Must turn left from ES to ENW over EW
        restrictTurn(true, EN + 1, ENW, EW);

        //Expected List of NO turns which are semantically the same as previous ONLY turn
        List<TurnRestriction> expectedTurnRestrictions = new ArrayList<>(5);
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,EN));
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,EE));
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,ES));
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,ENE));
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,EW+1,EW));
        expectedTurnRestrictions.add(makeTurnRestriction(false, EN+1,ESW,EW));

        TurnRestriction tr = streetLayer.turnRestrictions.get(0);

        LOG.debug("TR:{}", tr);
        List<TurnRestriction> trs = tr.remap(streetLayer);
        assertEquals("Remapped size differ", expectedTurnRestrictions.size(),trs.size());
        for(int i = 0; i < expectedTurnRestrictions.size(); i++) {
            TurnRestriction expected = expectedTurnRestrictions.get(i);
            TurnRestriction remapped = trs.get(i);
            assertEquals("FROM Edge: EX:" + expected + " !=  REM:" + remapped, expected.fromEdge, remapped.fromEdge);
            assertEquals("TO Edge: EX:" + expected + " !=  REM:" + remapped, expected.toEdge, remapped.toEdge);
            assertEquals("ONLY: EX:" + expected + " !=  REM:" + remapped, expected.only, remapped.only);
            assertTrue("VIA edges: EX:" + expected + " !=  REM:" + remapped, Arrays.equals(expected.viaEdges, remapped.viaEdges));
        }

    }

    @Test
    public void testSimpleOnlyTurnReverse () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VW);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VS);
        assertNotNull(state);

        // must turn right from ES to EE
        restrictTurn(true, ES + 1, EE);

        r = new StreetRouter(streetLayer);
        r.profileRequest.reverseSearch = true;
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VW);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(VS);

        LOG.debug("Reverse only turn:{}", restrictedState.compactDump(true));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);

        // The path should go through VE now because of the only turn restriction
        boolean foundVE = false;

        while (restrictedState != null) {
            if (restrictedState.vertex == VE) {
                foundVE = true;
                break;
            }

            restrictedState = restrictedState.backState;
        }

        assertTrue(foundVE);
    }

    //Tests if no turn works when Split is in destination
    @Test
    public void testNoTurnWithSplitDestination () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        Split split = streetLayer.findSplit(37.363, -122.1235, 100, null);

        StreetRouter.State state = r.getState(split);
        assertNotNull(state);

        LOG.debug("Normat with split:{}", state.compactDump(r.profileRequest.reverseSearch));

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State restrictedState = r.getState(split);

        LOG.debug("Normal restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);
    }

    //Tests if turn restrictions works when start is split in reverse search
    @Test
    public void testNoTurnWithSplitOriginReverse () {
        setUp(false);

        VertexStore.Vertex vs = streetLayer.vertexStore.getCursor(VS);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(37.363, -122.1235); //location of split
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VS); // getState(splitVS);
        assertNotNull(state);

        LOG.debug("Reverse with split:{}", state.compactDump(r.profileRequest.reverseSearch));


        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(37.363, -122.1235);
        //r.setOrigin(VW);
        r.route();

        StreetRouter.State restrictedState =  r.getStateAtVertex(VS); // getState(splitVS);

        LOG.debug("Reverse restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);

    }

    //Tests if splits on turn restriction on destination are supported
    @Test
    public void testNoTurnWithSplitReverse2 () {
        setUp(false);

        Split split = streetLayer.findSplit(37.363, -122.1235, 100, null);

        VertexStore.Vertex vs = streetLayer.vertexStore.getCursor(VS);
        Split splitVS = streetLayer.findSplit(vs.getLat(), vs.getLon(), 100, null);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VW); //location of split
        r.route();

        StreetRouter.State state = r.getState(splitVS);
        assertNotNull(state);

        LOG.debug("Reverse with split:{}", state.compactDump(r.profileRequest.reverseSearch));


        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VW);
        //r.setOrigin(VW);
        r.route();

        StreetRouter.State restrictedState =  r.getState(splitVS);

        LOG.debug("Reverse restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);

    }

    @Test
    public void testNoTurnBothSplit () {
        setUp(false);
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        assertTrue(r.setOrigin(37.3625, -122.123));
        r.route();

        Split dest = streetLayer.findSplit(37.363, -122.1235, 100, null);

        StreetRouter.State state = r.getState(dest);

        LOG.debug("Normal:{}", state.compactDump(r.profileRequest.reverseSearch));
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(37.3625, -122.123);
        r.route();

        StreetRouter.State restrictedState = r.getState(dest);

        LOG.debug("Restricted:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);
    }

    //Tests no turn with split at origin and turn restriction at origin
    @Test
    public void testNoTurnSplitOriginTurnRestriction () {
        setUp(false);
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        assertTrue(r.setOrigin(37.3625, -122.123));
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);

        LOG.debug("Normal:{}", state.compactDump(r.profileRequest.reverseSearch));
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(37.3625, -122.123);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(VW);

        LOG.debug("Restricted:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.weight > state.weight);
    }

    /** Test a no-U-turn with a via member */
    @Test
    public void testComplexNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VNW);

        LOG.debug("Normal: {} {}", stateFromN.dump(), stateFromN.compactDump(false));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VNW);

        restrictTurn(false, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VNW);

        LOG.debug("Normal restricted: {} {}", restrictedStateFromN.dump(), restrictedStateFromN.compactDump(false));

        // we should be forced to make a dipsy-doodle to avoid a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        // No U turn should not affect the left turn
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VNW);
        assertEquals(stateFromCenter.weight, restrictedStateFromCenter.weight);
    }

    /** Test a no-U-turn with a via member */
    @Test
    public void testComplexNoTurnRev () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VNW);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VN);

        LOG.debug("StateFromN:{} {}", stateFromN.dump(), stateFromN.compactDump(true));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VNW);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VCENTER);

        restrictTurn(false, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VNW);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VN);

        LOG.debug("Res StateFromN:{}", restrictedStateFromN.dump());

        // we should be forced to make a dipsy-doodle to avoid a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VNW);
        r.route();

        // No U turn should not affect the left turn
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VCENTER);
        assertEquals(stateFromCenter.weight, restrictedStateFromCenter.weight);
    }

    /** Test an only-turn with a via member */
    @Test
    public void testOnlyTurnWithViaMember () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VE);

        LOG.debug("Only via N: VN to VE {}", stateFromN.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromN, VNW));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VE);
        LOG.debug("Only via N: VCENTER to VE {}", stateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromCenter, VNW));


        restrictTurn(true, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VE);
        LOG.debug("Only via Restricted: VN to VE {}", restrictedStateFromN.compactDump(r.profileRequest.reverseSearch));

        // we should be forced to make a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        // Only U turn should not affect a state starting at the center
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VE);
        LOG.debug("Only via Restricted: VCENTER to VE {}", restrictedStateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertEquals(stateFromCenter.weight, restrictedStateFromCenter.weight);

        // make sure the state from the north goes through VNW as there's an only U turn restriction.
        assertTrue(stateContainsVertex(restrictedStateFromN, VNW));

    }

    @Test
    public void testOnlyTurnWithViaMemberReverse () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VE);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VN);
        LOG.debug("Rev: Only via N: VN to VE {}", stateFromN.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromN, VNW));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VE);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VCENTER);
        LOG.debug("Rev: Only via N: VCENTER to VE {}", stateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromCenter, VNW));


        restrictTurn(true, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VE);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VN);
        LOG.debug("Rev: Only via Restricted: VN to VE {}", restrictedStateFromN.compactDump(r.profileRequest.reverseSearch));

        // we should be forced to make a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(VE);
        r.route();

        // Only U turn should not affect a state starting at the center
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VCENTER);
        LOG.debug("Rev: Only via Restricted: VCenter to VE {}", restrictedStateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertEquals(stateFromCenter.weight, restrictedStateFromCenter.weight);

        // make sure the state from the north goes through VNW as there's an only U turn restriction.
        assertTrue(stateContainsVertex(restrictedStateFromN, VNW));

    }

    /** does having turn restrictions at adjacent intersections cause an infinite loop (issue #88) */
    @Test
    public void testAdjacentRestrictionInfiniteLoop () {
        setUp(false);

        restrictTurn(false, EW, ENW);
        restrictTurn(false, EW + 1, ES);

        RoutingVisitor countingVisitor = new RoutingVisitor() {
            int count;
            @Override
            public void visitVertex (StreetRouter.State state) {
                if (count++ > 1000) throw new CountExceededException();
            }
        };

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setRoutingVisitor(countingVisitor);
        r.setOrigin(VCENTER);

        try {
            r.route();
        } catch (CountExceededException e) {
            assertTrue("Search will progress infinitely", false);
        }
    }

    /** does having turn restrictions at adjacent intersections cause an infinite loop (issue #88) */
    @Test
    public void testAdjacentRestrictionInfiniteLoopReverse () {
        setUp(false);

        restrictTurn(false, EW, ENW);
        restrictTurn(false, EW + 1, ES);

        RoutingVisitor countingVisitor = new RoutingVisitor() {
            int count;
            @Override
            public void visitVertex (StreetRouter.State state) {
                if (count++ > 1000) throw new CountExceededException();
            }
        };

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setRoutingVisitor(countingVisitor);
        r.setOrigin(VCENTER);

        try {
            r.route();
        } catch (CountExceededException e) {
            assertTrue("Search will progress infinitely", false);
        }
    }

    /** does a state pass through a vertex? */
    public static boolean stateContainsVertex(StreetRouter.State state, int vertex) {
        while (state != null) {
            if (state.vertex == vertex) return true;
            state = state.backState;
        }
        return false;
    }

    private static class CountExceededException extends RuntimeException { /* nothing */ }
}
