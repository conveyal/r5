package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Function;


/**
 * The purpose of the TripScheduleSearch is to optimize the search for a trip schedule
 * for a given pattern. Normally the search scan trips sequentially, aborting when a
 * valid trip is found, it can do so because the trips are ordered after the FIRST stop
 * alight/board times. We also assume that trips do not pass each other; Hence
 * trips in service on a given day will be in order for all other stops too.
 * <p>
 * The search use a binary search if the number of trip schedules is above a
 * given threshold. A linear search is slow when the number of schedules is very
 * large, let say more than 300 trip schedules.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public interface TripScheduleSearch<T extends TripScheduleInfo> {
        /**
         * Find the best trip matching the given {@code timeLimit}.
         * This is the same as calling {@link #search(int, int, int)} with {@code tripIndexLimit: -1}.
         *
         * @see #search(int, int, int)
         */
        boolean search(int timeLimit, int stopPositionInPattern);

        /**
         * Find the best trip matching the given {@code timeLimit} and {@code tripIndexLimit}.
         *
         * @param tripIndexLimit   Lower bound for trip index to search for. Inclusive. Use {@code 0}
         *                              for an unbounded search.
         * @param timeLimit     The latest point in time a trip can arrive, exclusive.
         * @param stopPositionInPattern The stop to board
         */
        boolean search(int timeLimit, int stopPositionInPattern, int tripIndexLimit);


    T getCandidateTrip();

    int getCandidateTripIndex();


    /** Create a new trip schedule search */
    static <T extends TripScheduleInfo> TripScheduleSearch<T> create(
            boolean boardSearch,
            int sizeThreshold,
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback)
    {
        return boardSearch
                ? new TripScheduleBoardSearch<>(sizeThreshold, pattern, skipTripScheduleCallback)
                : new TripScheduleAlightSearch<>(sizeThreshold, pattern, skipTripScheduleCallback);
    }
}
