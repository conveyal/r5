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
    private boolean betterOrEqual(McRaptorSuboptimalPathProfileRouter.McRaptorState dominator, McRaptorSuboptimalPathProfileRouter.McRaptorState dominatee) {
        // FIXME add check for nonnegative

        // apply strict dominance if fares and transfer privileges are same
        if (dominator.fare.cumulativeFarePaid <= dominatee.fare.cumulativeFarePaid &&
                dominator.fare.transferAllowance.canTransferPrivilegeDominate(dominatee.fare.transferAllowance) &&
                //dominatorFare.transferAllowance.expirationTime >= dominateeFare.transferAllowance.expirationTime &&
                dominator.fare.transferAllowance.number >= dominatee.fare.transferAllowance.number) {
            return dominator.time <= dominatee.time;
        } else {
            // non-strict dominance
            int dominateeConsumedValue = dominatee.fare.cumulativeFarePaid - dominatee.fare.transferAllowance.value;
            // if you can get to this location using the dominator for less than the "consumed cost" -- i.e. the fare
            // minus any potential discounts -- of the dominatee, the dominatee is dominated.
            // TODO can I use geq here?
            return dominator.time <= dominatee.time && dominator.fare.cumulativeFarePaid <= dominateeConsumedValue;
        }
    }

    @Override
    public boolean add(McRaptorSuboptimalPathProfileRouter.McRaptorState newState) {
        // calculate fare if it has not been calculated before
        // this is not the best place to do this, as there are two FareDominatingLists per stop (for best and nontransfer
        // states), but it works.
        if (newState.fare == null) newState.fare = fareCalculator.calculateFare(newState);

        for (Iterator<McRaptorSuboptimalPathProfileRouter.McRaptorState> it = states.iterator(); it.hasNext();) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState existing = it.next();


            // Check first if the existing state is better than or equal to the new state. We check the existing state
            // vs the new state before doing the opposite, because two states may be equal (for instance, in Boston,
            // a trip from the Conveyal office at Mass Ave and Newbury to Alewife using CT1 -> Red and 1 -> Red are
            // equal if they both get you on the same red line train - they have the same time, and the same fare situation
            // (both leave you coming off the subway with a 2.25 fare privilige that can be used on any mode that has
            // discounted transfer). We prefer to save the state that was found first, to minimize churn. This also prefers
            // fewer-transfer routes, all else equal, because fewer-transfer routes are found before more-transfer routes
            // due to the RAPTOR algorithm.
            if (betterOrEqual(existing, newState)) {
                return false;
            }

            if (betterOrEqual(newState, existing)) {
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