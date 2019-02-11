package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;

import java.util.LinkedList;


/**
 * Abstract super class for multi-criteria stop arrival.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public abstract class AbstractStopArrival<T extends TripScheduleInfo> implements StopArrivalView<T> {

    public static <T extends TripScheduleInfo> ParetoComparator<AbstractStopArrival<T>> compareArrivalTimeRoundAndCost() {
        // This is important with respect to performance. Using logical OR(||) is faster than boolean OR(|)
        // and we intentionally do NOT use the ParetoComparatorBuilder.
        return (l, r) -> l.arrivalTime < r.arrivalTime || l.paretoRound < r.paretoRound || l.cost < r.cost;
    }

    public static <T extends TripScheduleInfo> ParetoComparator<AbstractStopArrival<T>> compareArrivalTimeAndRound() {
        // This is important with respect to performance. Using logical OR(||) is faster than boolean OR(|)
        // and we intentionally do NOT use the ParetoComparatorBuilder.
        return (l, r) -> l.arrivalTime < r.arrivalTime || l.paretoRound < r.paretoRound;
    }

    private final AbstractStopArrival<T> previous;

    /**
     * We want transits to dominate transfers so we increment the round not only between RangeRaptor rounds,
     * but for transits and transfers also. The access leg is paretoRound 0, the first transit leg is 1.
     * The following transfer leg, if it exist, is paretoRound 2, and the next transit is 3, and so on.
     * <p/>
     * The relationship between Range Raptor round and paretoRound can be described by this formula:
     * <pre>
     *     Range Raptor round =  (paretoRound + 1) / 2  // The divide by 2 rounds down (integer calculation)
     * </pre>
     */
    private final int paretoRound;
    private final int stop;
    private final int departureTime;
    private final int arrivalTime;
    private final int cost;

    /**
     * Transit or transfer.
     *
     * @param previous the previous arrival visited for the current trip
     * @param stop stop index for this arrival
     * @param departureTime the departure time from the previous stop
     * @param arrivalTime the arrival time for this stop index
     * @param additionalCost the accumulated cost at this stop arrival
     */
    AbstractStopArrival(AbstractStopArrival<T> previous, int stop, int departureTime, int arrivalTime, int additionalCost) {
        this.previous = previous;
        this.paretoRound = previous.paretoRound + (isTransitFollowedByTransit() ? 2 : 1);
        this.stop = stop;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.cost = previous.cost + additionalCost;
    }

    /**
     * Initial state - first stop visited.
     */
    AbstractStopArrival(int stop, int departureTime, int arrivalTime, int initialCost) {
        this.previous = null;
        this.paretoRound = 0;
        this.stop = stop;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.cost = initialCost;
    }


    @Override
    public final int round() {
        return (paretoRound + 1) / 2;
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

    @Override
    public Iterable<Integer> listStopsForDebugging() {
        LinkedList<Integer> stops = new LinkedList<>();
        for(AbstractStopArrival<?> it = this; it != null; it = it.previous) {
            stops.addFirst(it.stop);
        }
        return stops;
    }

    /**
     * This method is used to find the first transit arrival in a journey.
     * The first leg after the access leg must be a transit leg, so
     * the second leg is always the first transit leg/arrival.
     */
    boolean firstArrivedByTransit() {
        // We can use the paretoRound for this, because the two first leags are always
        // an access lag followed by a transit leg.
        return paretoRound == 1;
    }

    private boolean isTransitFollowedByTransit() {
        return arrivedByTransit() && previous.arrivedByTransit();
    }
}
