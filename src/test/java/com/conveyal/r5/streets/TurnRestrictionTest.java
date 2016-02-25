package com.conveyal.r5.streets;

import com.conveyal.r5.profile.Mode;
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
}