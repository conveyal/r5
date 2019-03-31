package com.conveyal.r5.otp2.rangeraptor.path.configure;


import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.transit.SearchContext;
import com.conveyal.r5.otp2.util.paretoset.ParetoComparator;

import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorStandard;
import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorWithCost;
import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorWithRelaxedCost;
import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorWithTimetable;
import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorWithTimetableAndCost;
import static com.conveyal.r5.otp2.rangeraptor.path.PathParetoSetComparators.comparatorWithTimetableAndRelaxedCost;

/**
 * This class is responsible for creating a a result collector - the
 * set of paths.
 * <p/>
 * This class have REQUEST scope, so a new instance should be created
 * for each new request/travel search.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class PathConfig<T extends TripScheduleInfo> {
    private final SearchContext<T> ctx;

    public PathConfig(SearchContext<T> context) {
        this.ctx = context;
    }

    /**
     * Create a new {@link DestinationArrivalPaths} each time it is invoked.
     * The given {@code includeCost} decide if the cost should be included in the
     * pareto set criteria or not.
     */
    public DestinationArrivalPaths<T> createDestArrivalPaths(boolean includeCost) {
        return new DestinationArrivalPaths<>(
                paretoComparator(includeCost),
                ctx.calculator(),
                ctx.debugFactory(),
                ctx.lifeCycle()
        );
    }

    private ParetoComparator<Path<T>> paretoComparator(boolean includeCost) {
        double relaxedCost = ctx.searchParams().relaxCostAtDestination();
        boolean includeRelaxedCost = includeCost && relaxedCost > 0.0;
        boolean includeTimetable = ctx.searchParams().timetableEnabled();


        if(includeTimetable && includeRelaxedCost) {
            return comparatorWithTimetableAndRelaxedCost(relaxedCost);
        }
        if(includeTimetable && includeCost) {
            return comparatorWithTimetableAndCost();
        }
        if(includeTimetable) {
            return comparatorWithTimetable();
        }
        if(includeRelaxedCost) {
            return comparatorWithRelaxedCost(relaxedCost);
        }
        if(includeCost) {
            return comparatorWithCost();
        }
        return comparatorStandard();
    }
}
