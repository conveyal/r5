package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Function;


/**
 * The purpose of this class is to optimize the search for a trip schedule for
 * a given pattern and stop. Normally the search scan from the upper bound index
 * and down, it can do so because the trips are ordered after the FIRST stop
 * alight/board times. We also assume that trips do not pass each other; Hence
 * trips in service on a given day will be in order for all other stops too.
 * <p>
 * The search use a binary search if the number of trip schedules is above a
 * given threshold. A linear search is slow when the number of schedules is very
 * large, let say more than 300 trip schedules.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class TripScheduleAlightSearch<T extends TripScheduleInfo> implements TripScheduleSearch<T> {
    private final int nTripsBinarySearchThreshold;
    private final TripPatternInfo<T> pattern;
    private final Function<T, Boolean> skipTripScheduleCallback;

    private int latestAlightTime;
    private int stopPositionInPattern;

    private T candidateTrip;
    private int candidateTripIndex;

    public TripScheduleAlightSearch(
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
     * Find the last trip arriving at the given stop BEFORE the given {@code latestAlightTime}.
     * This is the same as calling {@link #search(int, int, int)} with {@code tripIndexLowerBound: -1}.
     *
     * @see #search(int, int, int)
     */
    public boolean search(int latestAlightTime, int stopPositionInPattern) {
        return search(latestAlightTime, stopPositionInPattern, -1);
    }

    /**
     * Find the last trip arriving at the given stop BEFORE the given {@code latestAlightTime}, but
     * before the given trip ({@code tripIndexUpperBound}).
     *
     * @param tripIndexLowerBound   Lower bound for trip index to search for. Inclusive. Use {@code 0}
     *                              for an unbounded search.
     * @param latestAlightTime     The latest point in time a trip can arrive, exclusive.
     * @param stopPositionInPattern The stop to board
     */
    public boolean search(int latestAlightTime, int stopPositionInPattern, int tripIndexLowerBound) {
        this.latestAlightTime = latestAlightTime;
        this.stopPositionInPattern = stopPositionInPattern;
        this.candidateTrip = null;
        this.candidateTripIndex = -1;

        // No previous trip is found
        if(tripIndexLowerBound < 0) {
            if(pattern.numberOfTripSchedules() > nTripsBinarySearchThreshold) {
                return findFirstBoardingOptimizedForLargeSetOfTrips();
            }
            else {
                return findBoardingSearchForward(0);
            }
        }

        // We have a limited number of trips (no previous trip found) or already found a candidate
        // in a previous search; Hence searching forward from the lower bound is the fastest way to proceed.
        return findBoardingSearchForward(tripIndexLowerBound);
    }

    private boolean findFirstBoardingOptimizedForLargeSetOfTrips() {
        int indexBestGuess = binarySearchForTripIndex();

        // Use the best guess from the binary search to look for a candidate trip
        // We can not use upper bound to exit the search. We need to continue
        // until we find a trip in service.
        boolean found = findBoardingSearchForward(indexBestGuess);

        // If a valid result is found and we can return
        if(found) {
            return true;
        }

        // No trip schedule above the best guess was found. This may happen if enough
        // trips are not in service.
        //
        // So we have to search for the first valid trip schedule after that.
        return findBoardingSearchBackward(indexBestGuess);
    }

    /**
     * This method search for the last scheduled trip arriving before the {@code latestAlightTime}.
     * Only trips with a trip index greater than the given {@code tripIndexLowerBound} is considered.
     *
     * @param tripIndexLowerBound The trip index lower bound, where search start (inclusive).
     */
    private boolean findBoardingSearchForward(int tripIndexLowerBound) {
        final int N = pattern.numberOfTripSchedules();

        for(int i = tripIndexLowerBound; i < N;  ++i) {
            T trip = pattern.getTripSchedule(i);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int arrival = trip.arrival(stopPositionInPattern);

            if (arrival < latestAlightTime) {
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
     * This method search for the last scheduled trip arrival before the {@code latestAlightTime}.
     * Only trips with a trip index in the range: {@code [0..tripIndexUpperBound-1]} is considered.
     *
     * @param tripIndexUpperBound The trip index upper bound, where search end (exclusive).
     */
    private boolean findBoardingSearchBackward(final int tripIndexUpperBound) {
        for(int i = tripIndexUpperBound-1; i >=0; --i) {
            T trip = pattern.getTripSchedule(i);

            if (skipTripSchedule(trip)) {
                continue;
            }

            final int arrival = trip.arrival(stopPositionInPattern);

            if (arrival < latestAlightTime) {
                candidateTrip = trip;
                candidateTripIndex = i;
                return true;
            }
        }
        return false;
    }

    /**
     * Do a binary search to find the approximate lower bound index for where to start the search.
     * We IGNORE if the trip schedule is in service.
     *
     * @return a better upper bound index (exclusive)
     */
    private int binarySearchForTripIndex() {
        int lower = 0, upper = pattern.numberOfTripSchedules();

        // Do a binary search to find where to start the search.
        // We IGNORE if the trip schedule is in service.
        while (upper - lower > nTripsBinarySearchThreshold) {
            int m = (lower + upper) / 2;

            TripScheduleInfo trip = pattern.getTripSchedule(m);

            int arrival = trip.arrival(stopPositionInPattern);

            if (arrival < latestAlightTime) {
                lower = m;
            }
            else {
                upper = m;
            }
        }
        return lower;
    }

    /** Skip trips not running on the day of the search and frequency trips  */
    private boolean skipTripSchedule(T trip) {
        return skipTripScheduleCallback.apply(trip);
    }
}
