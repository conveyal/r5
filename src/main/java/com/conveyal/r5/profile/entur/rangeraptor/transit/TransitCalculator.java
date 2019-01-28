package com.conveyal.r5.profile.entur.rangeraptor.transit;


import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Function;

/**
 * The transit calculator is used to calculate transit related stuff, like calculating
 * <em>earliest boarding time</em> and time-shifting the access legs.
 * <p/>
 * The calculator is shared between the state, worker and path mapping code. This
 * make the calculations consistent and let us hide the request parameters. Hiding the
 * request parameters ensure that this calculator is used.
 */
public interface TransitCalculator {

    int add(int time, int delta);

    int sub(int time, int delta);

    int addBoardSlack(int time);

    int subBoardSlack(int time);

    boolean isBest(int subject, int candidate);

    int originDepartureTime(final int firstTransitBoardTime, final int accessLegDuration);

    int maxTimeLimit();

    int unreachedTime();

    /**
     * Indicate if a search is done forward or backward. From the journey origin to destination, or the
     * other way around.
     * <p/>
     * Try to avoid using this method, consider adding stuff to this class instead - that have
     * to implementation - that keeps the code clean and fast.
     *
     * @return true if a forward search.
     */
    boolean isAForwardSearch();

    /**
     * Return an iterator, iterating over the minutes in the RangeRaptor algorithm.
     */
    IntIterator rangeRaptorMinutes();

    /**
     * Return an iterator, iterating over the stop positions in a pattern.
     * Iterate from 0 to N-1 in a forward search and from
     * N-1 to 0 in a backwards search.
     */
    IntIterator patternStopIterator(TripPatternInfo<?> pattern);


    /**
     * Create a trip search, to use to find the correct trip to board/alight for
     * a given pattern. This is used to to inject a forward or backward
     * search into the worker (strategy design pattern).
     *
     * @param pattern the pattern to search
     * @param scheduledTripBinarySearchThreshold optimization limit for when to switch from binary to liniar serach.
     * @param skipTripScheduleCallback to support trip filtering, a callback is used to determin if the trip can be used or not.
     * @param <T> The TripSchedule type defined by the user of the range raptor API.
     * @return The trip search strategy implementation.
     */
    <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            int scheduledTripBinarySearchThreshold,
            Function<T, Boolean> skipTripScheduleCallback
    );

    /**
     * Return a calculator for test purpose, the {@link ForwardSearchTransitCalculator#rangeRaptorMinutes()}
     * behaviour is undefined - but this should be fine for most tests.
     */
    static TransitCalculator testDummy(int boardSlack) {
        return new ForwardSearchTransitCalculator(boardSlack, 0, 0, 0);
    }
}
