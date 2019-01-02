package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Function;


/**
 * The purpose of this class is to optimize the search for a trip schedule for
 * a given pattern and stop. Normally the search scan from the upper bound index
 * and down, it can do so because the trips are ordered after the FIRST stop
 * boarding times. We also assume that trips do not pass each other; Hence
 * trips in service on a given day will be in order for all other stops too.
 *
 * The search use a binary search if the number of trip schedules is above a
 * given threshold. A linear search is slow when the number of schedules is very
 * large, let say more than 300 trip schedules.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class TripScheduleBoardSearch<T extends TripScheduleInfo> {
    /**
     * The threshold is used to determine when to perform a binary search to reduce the
     * range to search in. The value ´46´ is based on testing with data from Entur and
     * all of Norway as a Graph. The performance curve is very flat, so choosing a value
     * between 10 and 100 does not affect the performance much.
     */
    final static int BINARY_SEARCH_THRESHOLD = 46;

    private final TripPatternInfo<T> pattern;
    private final Function<T, Boolean> skipTripScheduleCallback;

    public T candidateTrip;
    public int candidateTripIndex;

    public TripScheduleBoardSearch(TripPatternInfo<T> pattern, Function<T, Boolean> skipTripScheduleCallback) {
        this.pattern = pattern;
        this.skipTripScheduleCallback = skipTripScheduleCallback;
    }

    /**
     * Find the first trip leaving from the given stop AFTER the given {@code earliestBoardTime}.
     *
     * @param earliestBoardTime     The earliest point in time a trip can be boarded, exclusive.
     */
    public boolean search(int earliestBoardTime, int stopPositionInPattern) {
        return search(pattern.numberOfTripSchedules(), earliestBoardTime, stopPositionInPattern);
    }

    /**
     * Find the first trip leaving from the given stop AFTER the given {@code earliestBoardTime}, but
     * before the given trip ({@code tripIndexUpperBound}).
     *
     * @param tripIndexUpperBound   Upper bound for trip index to search for. Exclusive - search start at {@code tripIndexUpperBound - 1}
     * @param earliestBoardTime     The earliest point in time a trip can be boarded, exclusive.
     */
    public boolean search(int tripIndexUpperBound, int earliestBoardTime, int stopPositionInPattern) {
        if(tripIndexUpperBound <= BINARY_SEARCH_THRESHOLD) {
            return findFirstBoarding(tripIndexUpperBound, earliestBoardTime, stopPositionInPattern);
        }
        else {
            return findFirstBoardingOptimizedForLargeSetOfTrips(
                    tripIndexUpperBound,
                    earliestBoardTime,
                    stopPositionInPattern
            );
        }
    }

    /**
     * This method search for the first scheduled trip boarding after the given {@code earliestBoardTime }
     * considering only trips with a trip index below the given {@code tripIndexUpperBound}.
     *
     * It aborts if a trip schedule is found, or a trip in service is found but can not be boarded.
     *
     * @param tripIndexUpperBound Where the search start - exclusive.
     * @param earliestBoardTime the trip schedule boarding time must be larger than this to board.
     * @param stopPositionInPattern the boarding stop
     */
    private boolean findFirstBoarding(int tripIndexUpperBound, int earliestBoardTime, int stopPositionInPattern) {
        candidateTrip = null;

        for(int index = tripIndexUpperBound-1; index >= 0;  --index) {
            T trip = pattern.getTripSchedule(index);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int departure = trip.departure(stopPositionInPattern);

            if (departure > earliestBoardTime) {
                candidateTrip = trip;
                candidateTripIndex = index;
            }
            else {
                // this trip arrives too early. We can break out of the loop since
                // trips are sorted by departure time (trips in given schedule)
                // Trips passing another trip is not accounted for
                return candidateTrip != null;
            }
        }
        return candidateTrip != null;
    }

    private boolean findFirstBoardingOptimizedForLargeSetOfTrips(
            int tripIndexUpperBound,
            int earliestBoardTime,
            int stopPositionInPattern
    ) {
        int lower = 0, upper = tripIndexUpperBound;

        // Do a binary search to find where to start the search.
        // We IGNORE if the trip schedule is in service.
        while (upper - lower > BINARY_SEARCH_THRESHOLD) {
            int m = (lower + upper) / 2;

            TripScheduleInfo trip = pattern.getTripSchedule(m);

            int departure = trip.departure(stopPositionInPattern);

            if (departure > earliestBoardTime) {
                upper = m+1;
            }
            else {
                lower = m;
            }
        }

        // Use the upper bound from the binary search to look for a candidate trip
        // We can not use lower bound to exit the search. We need to continue
        // until we find a valid trip in service.
        boolean found = findFirstBoarding(upper, earliestBoardTime, stopPositionInPattern);

        // If a valid result is found and we can return
        if(found) { return true; }

        // No trip schedule below the upper bound was found. This may happen if enough
        // trips are not in service.
        //
        // So we have to search for the first valid trip schedule after that.
        for(int index = upper; index < tripIndexUpperBound ; ++index) {
            T trip = pattern.getTripSchedule(index);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int departure = trip.departure(stopPositionInPattern);

            // It would be tempting to skip this check, but we can not.
            // Trips schedules are only ordered for the first stop, not
            // necessarily for the rest - only trips in schedule can be
            // trusted to be in order - we ignored this when doing the
            // binary search.
            if (departure > earliestBoardTime) {
                candidateTrip = trip;
                candidateTripIndex = index;
                return true;
            }
        }
        return false;
    }

    /** Skip trips not running on the day of the search and frequency trips  */
    private boolean skipTripSchedule(T trip) {
        return skipTripScheduleCallback.apply(trip);
    }
}
