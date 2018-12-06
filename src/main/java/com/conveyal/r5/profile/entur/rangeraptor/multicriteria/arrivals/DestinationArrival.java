package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoComparator;


/**
 * Final destination arrival state. The purpose of this class is to keep track of all
 * pareto-optimal results. It eliminates the need to collect results after each
 * RangeRaptor iteration, and to apply a pareto filter on the paths returned. The
 * pareto function can be different from the other stops, because there is no performance
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
public final class DestinationArrival<T extends TripScheduleInfo> implements DestinationArrivalView<T> {

    private final TransitStopArrival<T> previous;
    private final int departureTime;
    private final int arrivalTime;
    private final int numberOfTransfers;
    private final int cost;
    private final int travelDuration;


    public DestinationArrival(TransitStopArrival<T> previous, int arrivalTime, int round, int cost) {
        this.previous = previous;
        this.arrivalTime = arrivalTime;
        this.numberOfTransfers = round - 1;
        this.cost = cost;
        this.departureTime = previous.arrivalTime();
        this.travelDuration = arrivalTime - accessStopArrival(previous).departureTime();
    }

    public static <T extends TripScheduleInfo> ParetoComparator<DestinationArrival<T>> compare_ArrivalTime_NumOfTransfers_Cost_And_TravelDuration() {
        return (l, r) ->
                l.arrivalTime < r.arrivalTime ||
                l.numberOfTransfers < r.numberOfTransfers ||
                l.cost < r.cost ||
                l.travelDuration < r.travelDuration;
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
        return "DestinationArrival{" +
                "previous arrival=" + previous +
                ", arrival time=" + arrivalTime +
                ", number of transfers=" + numberOfTransfers +
                ", cost=" + cost +
                ", travel duration=" + travelDuration +
                '}';
    }


    private static <T extends TripScheduleInfo> AbstractStopArrival<T> accessStopArrival(AbstractStopArrival<T> arrival) {
        AbstractStopArrival<T> it = arrival;
        while (!it.arrivedByAccessLeg()) {
            it = it.previous();
        }
        return it;
    }
}
