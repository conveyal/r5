package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TurnRestrictionTest extends TurnTest {

    private static final Logger LOG = LoggerFactory.getLogger(TurnRestrictionTest.class);
    @Test
    public void testSimpleNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(vw);
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State s1 = r.getStateAtVertex(vw);
        LOG.debug("turn rest: {} {}", s1.dump(), s1.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(s1.durationSeconds > state.durationSeconds);
    }


    @Test
    public void testSimpleNoTurnReverse () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vw);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(vs);
        LOG.debug("normal rev:{}", state.dump());
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.profileRequest.reverseSearch = true;
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vw);
        r.route();

        StreetRouter.State s1 = r.getStateAtVertex(vs);

        LOG.debug("turn rest rev:{}", s1.dump());

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(s1.durationSeconds > state.durationSeconds);
    }


    @Test
    public void testReverseTurnCosts() throws Exception {
        setUp(false);

        //Normal search
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(vw);
        assertNotNull(state);




        //Same reverse search
        new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vw);
        r.route();

        StreetRouter.State reverseState = r.getStateAtVertex(vs);
        assertNotNull(reverseState);
        assertEquals(state.durationSeconds, reverseState.durationSeconds);

    }

    @Test
    public void testSimpleOnlyTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(vw);
        assertNotNull(state);

        // must turn right from es to ee
        restrictTurn(true, es + 1, ee);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(vw);

        LOG.debug("Normal only turn:{}", restrictedState.compactDump(false));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);

        // The path should go through ve now because of the only turn restriction
        boolean foundVE = false;

        while (restrictedState != null) {
            if (restrictedState.vertex == ve) {
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

        //Must turn left from es to enw over ew
        restrictTurn(true, en + 1, enw, ew);

        //Expected List of NO turns which are semantically the same as previous ONLY turn
        List<TurnRestriction> expectedTurnRestrictions = new ArrayList<>(5);
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, en));
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, ee));
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, es));
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, ene));
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, ew +1, ew));
        expectedTurnRestrictions.add(makeTurnRestriction(false, en +1, esw, ew));

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
        r.setOrigin(vw);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(vs);
        assertNotNull(state);

        // must turn right from es to ee
        restrictTurn(true, es + 1, ee);

        r = new StreetRouter(streetLayer);
        r.profileRequest.reverseSearch = true;
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vw);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(vs);

        LOG.debug("Reverse only turn:{}", restrictedState.compactDump(true));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);

        // The path should go through ve now because of the only turn restriction
        boolean foundVE = false;

        while (restrictedState != null) {
            if (restrictedState.vertex == ve) {
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
        r.setOrigin(vs);
        r.route();

        Split split = streetLayer.findSplit(37.363, -122.1235, 100, StreetMode.CAR);

        StreetRouter.State state = r.getState(split);
        assertNotNull(state);

        LOG.debug("Normat with split:{}", state.compactDump(r.profileRequest.reverseSearch));

        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vs);
        r.route();

        StreetRouter.State restrictedState = r.getState(split);

        LOG.debug("Normal restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);
    }

    //Tests if turn restrictions works when start is split in reverse search
    @Test
    public void testNoTurnWithSplitOriginReverse () {
        setUp(false);

        VertexStore.Vertex vs = streetLayer.vertexStore.getCursor(this.vs);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(37.363, -122.1235); //location of split
        r.route();

        StreetRouter.State state = r.getStateAtVertex(this.vs); // getState(splitVS);
        assertNotNull(state);

        LOG.debug("Reverse with split:{}", state.compactDump(r.profileRequest.reverseSearch));


        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(37.363, -122.1235);
        //r.setOrigin(vw);
        r.route();

        StreetRouter.State restrictedState =  r.getStateAtVertex(this.vs); // getState(splitVS);

        LOG.debug("Reverse restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);

    }

    //Tests if splits on turn restriction on destination are supported
    @Test
    public void testNoTurnWithSplitReverse2 () {
        setUp(false);

        Split split = streetLayer.findSplit(37.363, -122.1235, 100, StreetMode.CAR);

        VertexStore.Vertex vs = streetLayer.vertexStore.getCursor(this.vs);
        Split splitVS = streetLayer.findSplit(vs.getLat(), vs.getLon(), 100, StreetMode.CAR);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vw); //location of split
        r.route();

        StreetRouter.State state = r.getState(splitVS);
        assertNotNull(state);

        LOG.debug("Reverse with split:{}", state.compactDump(r.profileRequest.reverseSearch));


        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vw);
        //r.setOrigin(vw);
        r.route();

        StreetRouter.State restrictedState =  r.getState(splitVS);

        LOG.debug("Reverse restricted with split:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);

    }

    @Test
    public void testNoTurnBothSplit () {
        setUp(false);
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        assertTrue(r.setOrigin(37.3625, -122.123));
        r.route();

        Split dest = streetLayer.findSplit(37.363, -122.1235, 100, StreetMode.CAR);

        StreetRouter.State state = r.getState(dest);

        LOG.debug("Normal:{}", state.compactDump(r.profileRequest.reverseSearch));
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(37.3625, -122.123);
        r.route();

        StreetRouter.State restrictedState = r.getState(dest);

        LOG.debug("Restricted:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);
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

        StreetRouter.State state = r.getStateAtVertex(vw);

        LOG.debug("Normal:{}", state.compactDump(r.profileRequest.reverseSearch));
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, es + 1, ew);

        r = new StreetRouter(streetLayer);
        r.streetMode = StreetMode.CAR;
        r.setOrigin(37.3625, -122.123);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(vw);

        LOG.debug("Restricted:{}", restrictedState.compactDump(r.profileRequest.reverseSearch));

        // durationSeconds should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(restrictedState.durationSeconds > state.durationSeconds);
    }

    /** Test a no-U-turn with a via member */
    @Test
    public void testComplexNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vn);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(vnw);

        LOG.debug("Normal: {} {}", stateFromN.dump(), stateFromN.compactDump(false));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vcenter);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(vnw);

        restrictTurn(false, en + 1, enw, ew);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vn);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(vnw);

        LOG.debug("Normal restricted: {} {}", restrictedStateFromN.dump(), restrictedStateFromN.compactDump(false));

        // we should be forced to make a dipsy-doodle to avoid a U-turn
        assertTrue(restrictedStateFromN.durationSeconds > stateFromN.durationSeconds);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vcenter);
        r.route();

        // No U turn should not affect the left turn
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(vnw);
        assertEquals(stateFromCenter.durationSeconds, restrictedStateFromCenter.durationSeconds);
    }

    /** Test a no-U-turn with a via member */
    @Test
    public void testComplexNoTurnRev () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vnw);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(vn);

        LOG.debug("StateFromN:{} {}", stateFromN.dump(), stateFromN.compactDump(true));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vnw);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(vcenter);

        restrictTurn(false, en + 1, enw, ew);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vnw);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(vn);

        LOG.debug("Res StateFromN:{}", restrictedStateFromN.dump());

        // we should be forced to make a dipsy-doodle to avoid a U-turn
        assertTrue(restrictedStateFromN.durationSeconds > stateFromN.durationSeconds);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(vnw);
        r.route();

        // No U turn should not affect the left turn
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(vcenter);
        assertEquals(stateFromCenter.durationSeconds, restrictedStateFromCenter.durationSeconds);
    }

    /** Test an only-turn with a via member */
    @Test
    public void testOnlyTurnWithViaMember () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vn);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(ve);

        LOG.debug("Only via N: vn to ve {}", stateFromN.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromN, vnw));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vcenter);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(ve);
        LOG.debug("Only via N: vcenter to ve {}", stateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromCenter, vnw));


        restrictTurn(true, en + 1, enw, ew);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vn);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(ve);
        LOG.debug("Only via Restricted: vn to ve {}", restrictedStateFromN.compactDump(r.profileRequest.reverseSearch));

        // we should be forced to make a U-turn
        assertTrue(restrictedStateFromN.durationSeconds > stateFromN.durationSeconds);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.setOrigin(vcenter);
        r.route();

        // Only U turn should not affect a state starting at the center
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(ve);
        LOG.debug("Only via Restricted: vcenter to ve {}", restrictedStateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertEquals(stateFromCenter.durationSeconds, restrictedStateFromCenter.durationSeconds);

        // make sure the state from the north goes through vnw as there's an only U turn restriction.
        assertTrue(stateContainsVertex(restrictedStateFromN, vnw));

    }

    @Test
    public void testOnlyTurnWithViaMemberReverse () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(ve);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(vn);
        LOG.debug("Rev: Only via N: vn to ve {}", stateFromN.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromN, vnw));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(ve);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(vcenter);
        LOG.debug("Rev: Only via N: vcenter to ve {}", stateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertFalse(stateContainsVertex(stateFromCenter, vnw));


        restrictTurn(true, en + 1, enw, ew);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(ve);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(vn);
        LOG.debug("Rev: Only via Restricted: vn to ve {}", restrictedStateFromN.compactDump(r.profileRequest.reverseSearch));

        // we should be forced to make a U-turn
        assertTrue(restrictedStateFromN.durationSeconds > stateFromN.durationSeconds);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.streetMode = StreetMode.CAR;
        r.profileRequest.reverseSearch = true;
        r.setOrigin(ve);
        r.route();

        // Only U turn should not affect a state starting at the center
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(vcenter);
        LOG.debug("Rev: Only via Restricted: VCenter to ve {}", restrictedStateFromCenter.compactDump(r.profileRequest.reverseSearch));
        assertEquals(stateFromCenter.durationSeconds, restrictedStateFromCenter.durationSeconds);

        // make sure the state from the north goes through vnw as there's an only U turn restriction.
        assertTrue(stateContainsVertex(restrictedStateFromN, vnw));

    }

    /** does having turn restrictions at adjacent intersections cause an infinite loop (issue #88) */
    @Test
    public void testAdjacentRestrictionInfiniteLoop () {
        setUp(false);

        restrictTurn(false, ew, enw);
        restrictTurn(false, ew + 1, es);

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
        r.setOrigin(vcenter);

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

        restrictTurn(false, ew, enw);
        restrictTurn(false, ew + 1, es);

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
        r.setOrigin(vcenter);

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
