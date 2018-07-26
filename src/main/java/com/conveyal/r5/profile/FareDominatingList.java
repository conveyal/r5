package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * An implementation of DominatingList, retaining pareto-optimal paths on time and fare.
 */
public class FareDominatingList implements DominatingList {
    private InRoutingFareCalculator fareCalculator;

    private LinkedList<McRaptorSuboptimalPathProfileRouter.McRaptorState> states = new LinkedList<>();

    public FareDominatingList(InRoutingFareCalculator fareCalculator) {
        this.fareCalculator = fareCalculator;
    }

    /** Is dominator strictly better than dominatee? */
    private boolean dominates (McRaptorSuboptimalPathProfileRouter.McRaptorState dominator, McRaptorSuboptimalPathProfileRouter.McRaptorState dominatee) {
        // apply strict dominance if fares and transfer privileges are same
        FareBounds dominatorFare = fareCalculator.calculateFare(dominator);
        FareBounds dominateeFare = fareCalculator.calculateFare(dominatee);
        if (dominatorFare.cumulativeFarePaid == dominateeFare.cumulativeFarePaid &&
                dominatorFare.transferAllowance.value == dominateeFare.transferAllowance.value &&
                dominatorFare.transferAllowance.expirationTime >= dominateeFare.transferAllowance.expirationTime &&
                dominatorFare.transferAllowance.number >= dominateeFare.transferAllowance.number) {
            return dominator.time < dominatee.time;
        } else {
            // non-strict dominance
            int dominateeConsumedValue = dominateeFare.cumulativeFarePaid - dominateeFare.transferAllowance.value;
            // if you can get to this location using the dominator for less than the "consumed cost" -- i.e. the fare
            // minus any potential discounts -- of the dominatee, the dominatee is dominated.
            return dominator.time < dominatee.time && dominatorFare.cumulativeFarePaid < dominateeConsumedValue;
        }
    }

    @Override
    public boolean add(McRaptorSuboptimalPathProfileRouter.McRaptorState newState) {
        for (Iterator<McRaptorSuboptimalPathProfileRouter.McRaptorState> it = states.iterator(); it.hasNext();) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState existing = it.next();

            // using geq/leq below to avoid codominant states in the same place on the pareto curve
            if (dominates(existing, newState)) {
                return false;
            }

            if (dominates(newState, existing)) {
                it.remove();
            }
        }

        // if we haven't returned false by now, state is nondominated.
        states.add(newState);
        return true;
    }

    @Override
    public Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates() {
        return states;
    }
}