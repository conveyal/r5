package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSortable;

import static com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction.createParetoFunctions;


/**
 * Final destination arrival state. The purose of this class is to keep track of all
 * pareto-optimal results. It eliminates the need to collect results after each
 * RangeRaptor iteration, and to apply a pareto filter on the paths returned. The
 * pareto function can be diffrent from the other stops, because there is no performance
 * penelty of haveing additional citeria.
 * <p/>
 * Commpared with the ParetoSet of each stop we need two extra criteria:
 * <ul>
 *     <li>Number of transfers. The McRangeRaptor works in rounds, so
 *     there is no need to include rounds in the intermediate stop pareto sets.
 *     But to avoid that a later iteration delete an earlier result with less
 *     transfares, transfers need to be added as a criterion to the final
 *     destination.
 *
 *     <li>Travel time duration - Range Raptor works in iteration. So when a
 *     later iteration makes it into the destination set - it should not erase
 *     an earlier result unless it is faster. There is no check on total travel
 *     duration for each stop, because it does not need to.
 *
 * </ul>
 */
public final class DestinationArrival<T extends TripScheduleInfo> implements ParetoSortable {
    /**
     * The pareto function MUST match the {@code ParetoSortable} implementation below
     */
    public static final ParetoFunction[] PARETO_FUNCTION = createParetoFunctions()
            .lessThen()  // arrival time
            .lessThen()  // number of transfers
            .lessThen()  // cost
            .lessThen()  // Travel time duration
            .build();

    private final TransitStopArrival<T> previousState;
    private final int arrivalTime;
    private final int numberOfTransfers;
    private final int cost;
    private final int travelDuration;


    public DestinationArrival(TransitStopArrival<T> previousState, int arrivalTime, int round, int cost) {
        this.previousState = previousState;
        this.arrivalTime = arrivalTime;
        this.numberOfTransfers = round - 1;
        this.cost = cost;
        this.travelDuration = arrivalTime - previousState.originFromTime();
    }

    public TransitStopArrival<T> getPreviousState() {
        return previousState;
    }

    public int getArrivalTime() {
        return arrivalTime;
    }

    public int getNumberOfTransfers() {
        return numberOfTransfers;
    }

    public int getTravelDuration() {
        return travelDuration;
    }

    @Override
    public int paretoValue1() {
        return arrivalTime;
    }

    @Override
    public int paretoValue2() {
        return numberOfTransfers;
    }

    @Override
    public int paretoValue3() {
        return cost;
    }

    @Override
    public int paretoValue4() {
        return travelDuration;
    }

    @Override
    public String toString() {
        return "DestinationArrival{" +
                "previousArrival=" + previousState +
                ", arrivalTime=" + arrivalTime +
                ", numberOfTransfers=" + numberOfTransfers +
                ", cost=" + cost +
                ", travelDuration=" + travelDuration +
                '}';
    }
}
