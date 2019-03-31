package com.conveyal.r5.otp2.rangeraptor.path;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.util.paretoset.ParetoComparator;


/**
 * List of different pareto set comparators. Earlier we created these dynamically,
 * but that affect the performance, so it is better to have one function for each
 * use case.
 * <p/>
 * All comparators include the "standard" set of criteria:
 * <ul>
 *     <li>Arrival Time</li>
 *     <li>Number of transfers</li>
 *     <li>Total travel duration time</li>
 * </ul>
 * The {@code travelDuration} is added as a criteria to the pareto comparator in addition to the parameters
 * used for each stop arrivals. The {@code travelDuration} is only needed at the destination because Range Raptor
 * works in iterations backwards in time.
 */
public class PathParetoSetComparators {

    /** Prevent this utility class from instantiation. */
    private PathParetoSetComparators() { }


    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorStandard() {
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds();
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorWithTimetable() {
        return (l, r) ->
                l.startTime() > r.startTime() ||
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds();
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorWithTimetableAndCost() {
        return (l, r) ->
                l.startTime() > r.startTime() ||
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds() ||
                l.cost() < r.cost();
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorWithTimetableAndRelaxedCost(
            double relaxCostAtDestinationArrival
    ) {
        return (l, r) ->
                l.startTime() > r.startTime() ||
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds() ||
                l.cost() < Math.round(r.cost() * relaxCostAtDestinationArrival);
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorWithCost() {
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds() ||
                l.cost() < r.cost();
    }

    public static <T extends TripScheduleInfo> ParetoComparator<Path<T>> comparatorWithRelaxedCost(
            double relaxCostAtDestinationArrival
    ) {
        return (l, r) ->
                l.endTime() < r.endTime() ||
                l.numberOfTransfers() < r.numberOfTransfers() ||
                l.totalTravelDurationInSeconds() < r.totalTravelDurationInSeconds() ||
                l.cost() < Math.round(r.cost() * relaxCostAtDestinationArrival);
    }
}
