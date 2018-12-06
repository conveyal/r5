package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.TimeUtils;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Abstract super class for multi-criteria stop arrival.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public abstract class AbstractStopArrival<T extends TripScheduleInfo> implements StopArrivalView<T> {

    public static <T extends TripScheduleInfo> ParetoComparator<AbstractStopArrival<T>> compareArrivalTimeRoundAndCost() {
        // This is important with respect to performance. Using logical OR(||) is faster than boolean OR(|)
        // and we intentionally do NOT use the ParetoComparatorBuilder.
        return (l, r) -> l.arrivalTime < r.arrivalTime || l.round < r.round || l.cost < r.cost;
    }

    public static <T extends TripScheduleInfo> ParetoComparator<AbstractStopArrival<T>> compareArrivalTimeAndRound() {
        // This is important with respect to performance. Using logical OR(||) is faster than boolean OR(|)
        // and we intentionally do NOT use the ParetoComparatorBuilder.
        return (l, r) -> l.arrivalTime < r.arrivalTime || l.round < r.round;
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

    @Override
    public List<Integer> listStops() {
        List<Integer> stops = new ArrayList<>();
        for(AbstractStopArrival<?> current = this; current != null; current = current.previous) {
            stops.add(current.stop);
        }
        Collections.reverse(stops);
        return stops;
    }
}
