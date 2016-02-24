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
}