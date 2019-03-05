package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DebugHandler;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is a thin wrapper around a ParetoSet of {@link DestinationArrival}s.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class DestinationArrivals<T extends TripScheduleInfo> {

    private final ParetoSet<DestinationArrival<T>> arrivals;
    private final DebugHandler<DestinationArrivalView<T>> debugHandler;
    private final TransitCalculator calculator;
    private boolean reachedCurrentRound = false;


    DestinationArrivals(
            double relaxCostAtDestinationArrival,
            TransitCalculator calculator,
            DebugHandlerFactory<T> debugHandlerFactory
    ) {
        this.arrivals = new ParetoSet<>(
                paretoComparator(relaxCostAtDestinationArrival),
                debugHandlerFactory.paretoSetDestinationArrivalListener()
        );
        this.debugHandler = debugHandlerFactory.debugDestinationArrival();
        this.calculator = calculator;
    }

    void transferToDestination(TransitStopArrival<T> lastTransitArrival, TransferLeg egressLeg, int cost) {
        DestinationArrival<T> newValue = new DestinationArrival<>(lastTransitArrival, egressLeg, cost);

        if(calculator.exceedsTimeLimit(newValue.arrivalTime())) {
            rejectByOptimization(newValue);
        }
        else {
            boolean added = add(newValue);
            if(added) {
                reachedCurrentRound = true;
            }
        }
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

    boolean isEmpty() {
        return arrivals.isEmpty();
    }

    boolean qualify(DestinationArrival<T> arrival) {
        return arrivals.qualify(arrival);
    }

    /**
     * Use this method to clear the flag for destination arrivals in the current round.
     */
    void clearReachedCurrentRoundFlag() {
        reachedCurrentRound = false;
    }

    <S> Collection<S> mapToList(Function<DestinationArrival<T>, S> mapper) {
        return arrivals.stream().map(mapper).collect(Collectors.toList());
    }


    /* private methods */

    private boolean add(DestinationArrival<T> arrival) {
        return arrivals.add(arrival);
    }

    private void rejectByOptimization(DestinationArrival<T> newValue) {
        if(debugHandler != null) {
            debugHandler.rejectByOptimization(newValue);
        }
    }

    private ParetoComparator<DestinationArrival<T>> paretoComparator(double relaxCostAtDestinationArrival) {
        // The `travelDuration` is added as a criteria to the pareto comparator in addition to the parameters
        // used for each stop arrivals. The `travelDuration` is only needed at the destination because Range Raptor
        // works in iterations backwards in time.
        return (l, r) ->
                l.arrivalTime() < r.arrivalTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.travelDuration() < r.travelDuration() ||
                l.cost() < Math.round(r.cost() * relaxCostAtDestinationArrival);
    }
}