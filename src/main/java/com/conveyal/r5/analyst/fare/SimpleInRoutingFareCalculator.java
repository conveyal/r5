package com.conveyal.r5.analyst.fare;

import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;

/**
 * A simple greedy fare calculator that simply applies a single fare at each boarding.
 */
public class SimpleInRoutingFareCalculator extends InRoutingFareCalculator {
    public int fare;

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        int fareForState = 0;

        while (state != null) {
            if (state.pattern != -1) fareForState += fare;
            state = state.back;
        }

        return new FareBounds(fareForState, new TransferAllowance());
    }

    @Override
    public String getType() {
        return "simple";
    }
}
