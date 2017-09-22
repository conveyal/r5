package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.GreedyFareCalculator;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * An implementation of DominatingList, retaining pareto-optimal paths on time and fare.
 */
public class FareDominatingList implements DominatingList {
    private GreedyFareCalculator fareCalculator;

    private LinkedList<McRaptorSuboptimalPathProfileRouter.McRaptorState> states = new LinkedList<>();

    public FareDominatingList(GreedyFareCalculator fareCalculator) {
        this.fareCalculator = fareCalculator;
    }

    @Override
    public boolean add(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {
        int thisFare = fareCalculator.calculateFare(state);

        for (Iterator<McRaptorSuboptimalPathProfileRouter.McRaptorState> it = states.iterator(); it.hasNext();) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState other = it.next();

            int otherFare = fareCalculator.calculateFare(other);

            // using geq/leq below to avoid codominant states in the same place on the pareto curve
            if (other.time <= state.time && otherFare <= thisFare) {
                return false;
            }

            if (other.time >= state.time && otherFare >= thisFare) {
                it.remove();
            }
        }

        // if we haven't returned false by now, state is nondominated.
        states.add(state);
        return true;
    }

    @Override
    public Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates() {
        return states;
    }
}
