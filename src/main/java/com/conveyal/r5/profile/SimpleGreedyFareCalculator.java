package com.conveyal.r5.profile;

/**
 * A simple greedy fare calculator that simply applies a single fare at each boarding.
 */
public class SimpleGreedyFareCalculator extends GreedyFareCalculator {
    public int fare;

    @Override
    public int calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {
        int fareForState = 0;

        while (state != null) {
            if (state.pattern != -1) fareForState += fare;
            state = state.back;
        }

        return fareForState;
    }

    @Override
    public String getType() {
        return "simple";
    }
}
