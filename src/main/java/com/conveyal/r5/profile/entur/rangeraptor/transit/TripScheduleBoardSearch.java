package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Function;


/**
 * The purpose of this class is to optimize the search for a trip schedule for
 * a given pattern and stop. Normally the search scan from the upper bound index
 * and down, it can do so because the trips are ordered after the FIRST stop
 * boarding times. We also assume that trips do not pass each other; Hence
 * trips IN SERVICE on a given day will be in order for all other stops too.
 * <p/>
 * The search use a binary search if the number of trip schedules is above a
 * given threshold. A linear search is slow when the number of schedules is very
 * large, let say more than 300 trip schedules.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class TripScheduleBoardSearch<T extends TripScheduleInfo> implements TripScheduleSearch<T> {
    private final int nTripsBinarySearchThreshold;
    private final TripPatternInfo<T> pattern;
    private final Function<T, Boolean> skipTripScheduleCallback;

    private int earliestBoardTime;
    private int stopPositionInPattern;

    private T candidateTrip;
    private int candidateTripIndex;

    public TripScheduleBoardSearch(
            int scheduledTripBinarySearchThreshold,
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        this.nTripsBinarySearchThreshold = scheduledTripBinarySearchThreshold;
        this.pattern = pattern;
        this.skipTripScheduleCallback = skipTripScheduleCallback;
    }

    @Override
    public T getCandidateTrip() {
        return candidateTrip;
    }

    @Override
    public int getCandidateTripIndex() {
        return candidateTripIndex;
    }

    /**
     * Find the first trip leaving from the given stop AFTER the given {@code earliestBoardTime}.
     * This is the same as calling {@link #search(int, int, int)} with {@code tripIndexUpperBound: -1}.
     *
     * @see #search(int, int, int)
     */
    public boolean search(int earliestBoardTime, int stopPositionInPattern) {
        return search(earliestBoardTime, stopPositionInPattern, -1);
    }

    /**
     * Find the first trip leaving from the given stop AFTER the given {@code earliestBoardTime}, but
     * before the given trip ({@code tripIndexUpperBound}).
     *
     * @param tripIndexUpperBound   Upper bound for trip index to search for. Exclusive - search start
     *                              at {@code tripIndexUpperBound - 1}. Use {@code -1} (negative value)
     *                              for an unbounded search.
     * @param earliestBoardTime     The earliest point in time a trip can be boarded, exclusive.
     * @param stopPositionInPattern The stop to board
     */
    public boolean search(int earliestBoardTime, int stopPositionInPattern, int tripIndexUpperBound) {
        this.earliestBoardTime = earliestBoardTime;
        this.stopPositionInPattern = stopPositionInPattern;
        this.candidateTrip = null;
        this.candidateTripIndex = -1;

        // No previous trip is found
        if(tripIndexUpperBound < 0) {
            // Use number of schedules as upper bound
            tripIndexUpperBound = pattern.numberOfTripSchedules();

            if(tripIndexUpperBound > nTripsBinarySearchThreshold) {
                return findFirstBoardingOptimizedForLargeSetOfTrips(tripIndexUpperBound);
            }
            // else perform default search
        }

        // We have a limited number of trips (no previous trip found) or already found a candidate
        // in a previous search; Hence searching backwards from the upper bound is the fastest way to proceed.
        return findBoardingSearchBackwards(tripIndexUpperBound);
    }

    private boolean findFirstBoardingOptimizedForLargeSetOfTrips(final int tripIndexUpperBound) {
        int bestGuess = binarySearchForTripIndex(tripIndexUpperBound);

        // Use the upper bound from the binary search to look for a candidate trip
        // We can not use lower bound to exit the search. We need to continue
        // until we find a valid trip in service.
        boolean found = findBoardingSearchBackwards(bestGuess);

        // If a valid result is found and we can return
        if(found) {
            return true;
        }

        // No trip schedule below the upper bound was found. This may happen if enough
        // trips are not in service.
        //
        // So we have to search for the first valid trip schedule after that.
        return findBoardingSearchForward(bestGuess, tripIndexUpperBound);
    }

    /**
     * This method search for the first scheduled trip boarding, after the given {@code earliestBoardTime}.
     * Only trips with a trip index smaller than the given {@code tripIndexUpperBound} is considered.
     * <p/>
     * The search start with trip {@code tripIndexUpperBound - 1} and search down towards index 0 (inclusive).
     *
     * @param tripIndexUpperBound The trip index upper bound, where search start (exclusive).
     */
    private boolean findBoardingSearchBackwards(int tripIndexUpperBound) {
        for(int i = tripIndexUpperBound-1; i >= 0;  --i) {
            T trip = pattern.getTripSchedule(i);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int departure = trip.departure(stopPositionInPattern);

            if (departure > earliestBoardTime) {
                candidateTrip = trip;
                candidateTripIndex = i;
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

    /**
     * This method search for the first scheduled trip boarding, after the given {@code earliestBoardTime}.
     * Only trips with a trip index in the range: {@code [tripIndexLowerBound..tripIndexUpperBound-1]} is considered.
     *
     * @param tripIndexLowerBound The trip index lower bound, where search start (inclusive).
     * @param tripIndexUpperBound The trip index upper bound, where search end (exclusive).
     */
    private boolean findBoardingSearchForward(final int tripIndexLowerBound, final int tripIndexUpperBound) {
        for(int i = tripIndexLowerBound; i < tripIndexUpperBound; ++i) {
            T trip = pattern.getTripSchedule(i);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int departure = trip.departure(stopPositionInPattern);

            if (departure > earliestBoardTime) {
                candidateTrip = trip;
                candidateTripIndex = i;
                return true;
            }
        }
        return false;
    }

    /**
     * Do a binary search to find the approximate upper bound index for where to start the search.
     * We IGNORE if the trip schedule is in service.
     * <p/>
     * This is just a guess and we return when there is at least one trip with a departure
     * time after the {@code earliestBoardTime} in the range.
     *
     * @return a better upper bound index (exclusive)
     */
    private int binarySearchForTripIndex(final int absoluteUpperBound) {
        int lower = 0, upper = absoluteUpperBound;

        // Do a binary search to find where to start the search.
        // We IGNORE if the trip schedule is in service.
        while (upper - lower > nTripsBinarySearchThreshold) {
            int m = (lower + upper) / 2;

            TripScheduleInfo trip = pattern.getTripSchedule(m);

            int departure = trip.departure(stopPositionInPattern);

            if (departure > earliestBoardTime) {
                upper = m;
            }
            else {
                lower = m;
            }
        }
        // Add one(+1) to upper bound to be sure at least one valid trip is
        // within the binary search result window (exclusive).
        return upper == absoluteUpperBound ? upper : upper + 1;
    }

    /** Skip trips not running on the day of the search and frequency trips  */
    private boolean skipTripSchedule(T trip) {
        return skipTripScheduleCallback.apply(trip);
    }
}
