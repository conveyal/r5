package com.conveyal.r5.streets;

import com.conveyal.r5.profile.Mode;
import com.sun.xml.internal.bind.annotation.OverrideAnnotationOf;
import org.junit.Test;

public class TurnRestrictionTest extends TurnTest {
    @Test
    public void testSimpleNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(r.getStateAtVertex(VW).weight > state.weight);
    }

    @Test
    public void testSimpleOnlyTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State state = r.getStateAtVertex(VW);
        assertNotNull(state);

        // must turn right from ES to EE
        restrictTurn(true, ES + 1, EE);

        r = new StreetRouter(streetLayer);
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        StreetRouter.State restrictedState = r.getStateAtVertex(VW);

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
    public void testNoTurnWithSplit () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        Split split = streetLayer.findSplit(37.363, -122.1235, 100);

        StreetRouter.State state = r.getState(split);
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(r.getState(split).weight > state.weight);
    }

    @Test
    public void testNoTurnBothSplit () {
        setUp(false);
        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        assertTrue(r.setOrigin(37.3625, -122.123));
        r.route();

        Split dest = streetLayer.findSplit(37.363, -122.1235, 100);

        StreetRouter.State state = r.getState(dest);
        assertNotNull(state);

        // create a turn restriction
        restrictTurn(false, ES + 1, EW);

        r = new StreetRouter(streetLayer);
        r.mode = Mode.CAR;
        r.setOrigin(VS);
        r.route();

        // weight should be greater because path should now include going past the intersection and making a U-turn back at it.
        assertTrue(r.getState(dest).weight > state.weight);
    }

    /** Test a no-U-turn with a via member */
    @Test
    public void testComplexNoTurn () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VNW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VNW);

        restrictTurn(false, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VNW);

        // we should be forced to make a dipsy-doodle to avoid a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        // No U turn should not affect the left turn
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VNW);
        assertEquals(stateFromCenter.weight, restrictedStateFromCenter.weight);
    }

    /** Test an only-turn with a via member */
    @Test
    public void testOnlyTurnWithViaMember () {
        setUp(false);

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State stateFromN = r.getStateAtVertex(VE);
        assertFalse(stateContainsVertex(stateFromN, VNW));

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        StreetRouter.State stateFromCenter = r.getStateAtVertex(VE);
        assertFalse(stateContainsVertex(stateFromCenter, VNW));


        restrictTurn(true, EN + 1, ENW, EW);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VN);
        r.route();

        StreetRouter.State restrictedStateFromN = r.getStateAtVertex(VE);

        // we should be forced to make a U-turn
        assertTrue(restrictedStateFromN.weight > stateFromN.weight);

        r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
        r.setOrigin(VCENTER);
        r.route();

        // Only U turn should not affect a state starting at the center
        StreetRouter.State restrictedStateFromCenter = r.getStateAtVertex(VE);
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

        RoutingVisitor countingVisitor = new RoutingVisitor(streetLayer.edgeStore, Mode.CAR) {
            public int count;

            @Override
            public void visitVertex (StreetRouter.State state) {
                if (count++ > 1000) throw new CountExceededException();
            }
        };

        StreetRouter r = new StreetRouter(streetLayer);
        // turn restrictions only apply to cars
        r.mode = Mode.CAR;
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