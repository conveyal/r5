package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.DestinationHeuristic;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

/**
 * The destination arrival state. The purpose of this class is to keep track of all
 * pareto-optimal results and candidates. It eliminates the need to collect results after each
 * RangeRaptor iteration, and to apply a pareto filter on the paths returned. The pareto
 * function can be different from the other stops, because there is a very small performance
 * penalty of having additional criteria.
 * <p/>
 * Compared with the ParetoSet of each stop we need two extra criteria:
 * <ul>
 * <li>Number of transfers. The McRangeRaptor works in rounds, so
 * there is no need to include rounds in the intermediate stop pareto sets.
 * But to avoid that a later iteration delete an earlier result with less
 * transfers, transfers need to be added as a criterion to the final destination.
 *
 * <li>Travel time duration - Range Raptor works in iteration. So when a
 * later iteration makes it into the destination set - it should not erase
 * an earlier result unless it is faster. There is no check on total travel
 * duration for each stop, because it does not need to.
 *
 * </ul>
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class DestinationArrival<T extends TripScheduleInfo> implements DestinationArrivalView<T> {
    /** Constant used to return an unknown value in cases where we do not care what is returned. */
    private static final int UNKNOWN_VALUE = -1;

    private final TransitStopArrival<T> previous;
    private final int departureTime;
    private final int arrivalTime;
    private final int numberOfTransfers;
    private final int travelDuration;
    private final int cost;


    public DestinationArrival(TransitStopArrival<T> previous, TransferLeg egressLeg, int additionalCost) {
        this.previous = previous;
        this.departureTime = previous.arrivalTime();
        this.arrivalTime = previous.arrivalTime() + egressLeg.durationInSeconds();
        this.numberOfTransfers = previous.round() - 1;
        this.travelDuration = previous.travelDuration() + egressLeg.durationInSeconds();
        this.cost = previous.cost() + additionalCost;
    }

    /**
     * This is used to make an optimistic guess for the best possible arrival at the destination,
     * using the given arrival and a pre-calculated heuristics.
     */
    public DestinationArrival(AbstractStopArrival<T> a, DestinationHeuristic h) {
        this.previous = null;
        this.departureTime = UNKNOWN_VALUE;
        this.arrivalTime = a.arrivalTime() + h.getMinTravelTime();
        this.numberOfTransfers = a.round() - 1 + h.getMinNumTransfers();
        this.travelDuration = a.travelDuration() + h.getMinTravelTime();
        this.cost = a.cost() + h.getMinCost();
    }

    @Override
    public int departureTime() {
        return departureTime;
    }

    @Override
    public int arrivalTime() {
        return arrivalTime;
    }

    public int numberOfTransfers() {
        return numberOfTransfers;
    }

    public int travelDuration() {
        return travelDuration;
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public StopArrivalView<T> previous() {
        return previous;
    }

    @Override
    public String toString() {
        return asString();
    }

    @Override
    public boolean equals(Object o) {
        throw new IllegalStateException("Avoid using hashCode() and equals() for this class.");
    }

    @Override
    public int hashCode() {
        throw new IllegalStateException("Avoid using hashCode() and equals() for this class.");
    }
}
