package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class Destination<T extends TripScheduleInfo> extends ParetoSet<DestinationArrival<T>> {

    private final DebugHandler<DestinationArrivalView<T>> debugHandler;
    private final TransitCalculator calculator;
    private boolean reachedCurrentRound = false;


    Destination(TransitCalculator calculator, DebugHandler<DestinationArrivalView<T>> debugHandler) {
        // The `travelDuration` is added as a criteria to the pareto comparator in addition to the parameters
        // used for each stop arrivals. The `travelDuration` is only needed at the destination because Range Raptor
        // works in iterations backwards in time.
        super((l, r) ->
                l.arrivalTime() < r.arrivalTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.cost() < r.cost() ||
                l.travelDuration() < r.travelDuration(),
                debugHandler::drop
        );
        this.calculator = calculator;
        this.debugHandler = debugHandler;
    }

    void transferToDestination(TransitStopArrival<T> lastTransitArrival, TransferLeg egressLeg, int cost) {
        DestinationArrival<T> newValue = new DestinationArrival<>(
                lastTransitArrival,
                egressLeg,
                cost
        );
        boolean added = false;

        if(!calculator.exceedsTimeLimit(newValue.arrivalTime())) {
            added = add(newValue);

            if(added) {
                reachedCurrentRound = true;
            }
        }
        notifyDebuggerOfNewArrival(newValue, added);
    }

    /**
     * Check if destination was reached in the current round.
     * <p/>
     * NOTE! Remember to clear flag before or after each round:
     * {@link #clearReachedCurrentRoundFlag()}.
     */
    boolean reachedCurrentRound() {
        return reachedCurrentRound;
    }

    /**
     * Use this method to clear the flag for destination arrivals in the current round.
     */
    void clearReachedCurrentRoundFlag() {
        reachedCurrentRound = false;
    }

    private void notifyDebuggerOfNewArrival(DestinationArrival<T> newValue, boolean added) {
        if(added) {
            debugHandler.accept(newValue, this);
        }
        else {
            debugHandler.reject(newValue, this);
        }
    }
}