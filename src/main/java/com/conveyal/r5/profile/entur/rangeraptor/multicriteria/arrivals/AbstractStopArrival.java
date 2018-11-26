package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.TimeUtils;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparatorBuilder;

import java.util.LinkedList;
import java.util.List;


/**
 * Abstract super class for multi-criteria stop arrival.
 * <p/>
 */
public abstract class AbstractStopArrival<T extends TripScheduleInfo> implements StopArrivalView<T> {

    public static <T extends TripScheduleInfo> ParetoComparator<AbstractStopArrival<T>> paretoComparator() {
        return new ParetoComparatorBuilder<AbstractStopArrival<T>>()
                .lessThen((v) -> v.arrivalTime)
                .lessThen((v) -> v.round)
                .lessThen((v) -> v.cost)
                .build();
    }

    private final AbstractStopArrival<T> previous;
    private final int round;
    private final int stop;
    private final int departureTime;
    private final int arrivalTime;
    private final int cost;

    /**
     * Transit or transfer
     */
    AbstractStopArrival(AbstractStopArrival<T> previous, int round, int stop, int departureTime, int arrivalTime, int cost) {
        this.previous = previous;
        this.round = round;
        this.stop = stop;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.cost = cost;
    }

    /**
     * Initial state - first stop visited.
     */
    AbstractStopArrival(int stop, int departureTime, int arrivalTime, int initialCost) {
        this.previous = null;
        this.round = 0;
        this.stop = stop;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.cost = initialCost;
    }


    @Override
    public final int round() {
        return round;
    }

    @Override
    public final int stop() {
        return stop;
    }

    @Override
    public int departureTime() {
        return departureTime;
    }

    @Override
    public final int arrivalTime() {
        return arrivalTime;
    }

    /**
     * @return previous state or throw a NPE if no previousArrival exist.
     */
    @SuppressWarnings({"ConstantConditions"})
    final int previousStop() {
        return previous.stop;
    }

    @Override
    public final AbstractStopArrival<T> previous() {
        return previous;
    }

    /**
     * This method is used to list all transits found in the last round. It is
     * overridden in the {@link TransitStopArrival} class.
     */
    public boolean arrivedByTransitLastRound() {
        return false;
    }

    public int cost() {
        return cost;
    }

    @Override
    public String toString() {
        return asString();
    }

    private String asString() {
        return String.format(
                "%s [%d, %5d] Time: %s - %s, Cost: %d",
                getClass().getSimpleName(),
                round(),
                stop(),
                TimeUtils.timeToStrCompact(departureTime()),
                TimeUtils.timeToStrCompact(arrivalTime()),
                cost()
        );
    }

    String asString(String details) {
        return asString() + " - " + details;
    }


    public void debug() {
        DebugState.debugStop(this);
    }

    public List<AbstractStopArrival<T>> path() {
        List<AbstractStopArrival<T>> path = new LinkedList<>();
        AbstractStopArrival<T> current = this;

        path.add(current);

        //noinspection ConstantConditions
        while (!current.arrivedByAccessLeg()) {
            current = current.previous;
            path.add(0, current);
        }
        return path;
    }
}
