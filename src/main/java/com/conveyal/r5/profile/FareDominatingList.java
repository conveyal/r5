package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * An implementation of DominatingList, retaining pareto-optimal paths on time and fare.
 */
public class FareDominatingList implements DominatingList {
    private final int maxFare;
    private final int maxClockTime;
    private InRoutingFareCalculator fareCalculator;

    private LinkedList<McRaptorSuboptimalPathProfileRouter.McRaptorState> states = new LinkedList<>();

    public FareDominatingList(InRoutingFareCalculator fareCalculator, int maxFare, int maxClockTime) {
        this.fareCalculator = fareCalculator;
        this.maxFare = maxFare;
        this.maxClockTime = maxClockTime;
    }

    /**
     * Return true if there is no way that a route with dominator as a prefix can yield a route that is slower or more
     * expensive than the same route with dominatee as a prefix.
     */
    private boolean betterOrEqual(McRaptorSuboptimalPathProfileRouter.McRaptorState dominator, McRaptorSuboptimalPathProfileRouter.McRaptorState dominatee) {
        // FIXME add check for nonnegative

        int dominateeConsumedValue = dominatee.fare.cumulativeFarePaid - dominatee.fare.transferAllowance.value;
        if (dominator.time <= dominatee.time) {
            // this route is as good or better on time
            if (dominator.fare.cumulativeFarePaid <= dominateeConsumedValue) {
                // This route is as fast as the alternate route, and it costs no more than the fare paid for the other route
                // minus any transfer priviliges that the user gets from the other route that could be realized in the future.
                return true;
            }

            // if the out of pocket cost is the same or less and the transfer privilege is as good as or better than the
            // other transfer allowance (exact definition depends on the system, see javadoc), then there is no way that
            // dominatee could yield a better fare than dominator.
            if (dominator.fare.cumulativeFarePaid <= dominatee.fare.cumulativeFarePaid &&
                    dominator.fare.transferAllowance.atLeastAsGoodForAllFutureRedemptions(dominatee.fare.transferAllowance)) {
                return true;
            }
        }

        // if we have not returned true by now, there may be a way that the dominatee can yield a faster or cheaper route
        return false;
    }

    @Override
    public boolean add(McRaptorSuboptimalPathProfileRouter.McRaptorState newState) {
        // if it is past the time limit, drop it
        if (newState.time > maxClockTime) return false;

        // calculate fare if it has not been calculated before
        // this is not the best place to do this, as there are two FareDominatingLists per stop (for best and nontransfer
        // states), but it works.
        if (newState.fare == null) newState.fare = fareCalculator.calculateFare(newState, maxClockTime);

        // Prune if the fare paid _minus the transfer privilege_ exceeds the max fare, for efficient calculation.
        // This is in order to support subway systems where the cumulative fare paid may actually go _down_ after an
        // additional ride.

        // For instance, in the San Francisco Bay Area Rapid Transit (BART) system, the fare to travel from the San
        // Francisco International Airport (SFO) to San Bruno is $7.85 (using a Clipper contactless smartcard). But if the
        // user then crosses the platform and boards a Millbrae-bound train and exits at Millbrae, their total fare will
        // be only $4.55 (i.e. riding another transit vehicle will actually reduce the fare). This is an optimal trip
        // at some times of day when direct SFO-Millbrae service is not running. If we cut off the search when cumulativeFarePaid
        // exceeded, say, $5, we'd prevent this trip. But if cumulativeFarePaid is set to $7.85 when alighting at San
        // Bruno, and transferAllowance.value is set to $7.85 - $4.55 = $3.30, we will retain it properly.
        if (newState.fare.cumulativeFarePaid - newState.fare.transferAllowance.value > maxFare) return false;

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