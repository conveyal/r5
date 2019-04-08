package com.conveyal.r5.otp2.rangeraptor.transit;

import com.conveyal.r5.otp2.api.request.SearchParams;
import com.conveyal.r5.otp2.api.request.TuningParameters;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TripPatternInfo;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.path.PathMapper;
import com.conveyal.r5.otp2.rangeraptor.path.ReversePathMapper;
import com.conveyal.r5.otp2.util.IntIterators;
import com.conveyal.r5.otp2.util.TimeUtils;

import java.util.function.Function;

/**
 * A calculator that will take you back in time not forward, this is the
 * basic logic to implement a reveres search.
 */
final class ReverseSearchTransitCalculator implements TransitCalculator {
    private final int tripSearchBinarySearchThreshold;
    private final int boardSlackInSeconds;
    private final int latestArrivalTime;
    private final int searchWindowInSeconds;
    private final int earliestAcceptableDepartureTime;
    private final int iterationStep;

    ReverseSearchTransitCalculator(SearchParams s, TuningParameters t) {
        // The request is already modified to search backwards, so 'earliestDepartureTime()'
        // goes with destination and 'latestArrivalTime()' match origin.
        this(
                t.scheduledTripBinarySearchThreshold(),
                s.boardSlackInSeconds(),
                s.latestArrivalTime(),
                s.searchWindowInSeconds(),
                s.earliestDepartureTime(),
                t.iterationDepartureStepInSeconds()
        );
    }

    ReverseSearchTransitCalculator(
            int binaryTripSearchThreshold,
            int boardSlackInSeconds,
            int latestArrivalTime,
            int searchWindowInSeconds,
            int earliestAcceptableDepartureTime,
            int iterationStep
    ) {
        this.tripSearchBinarySearchThreshold = binaryTripSearchThreshold;
        this.boardSlackInSeconds = boardSlackInSeconds;
        this.latestArrivalTime = latestArrivalTime;
        this.searchWindowInSeconds = searchWindowInSeconds;
        this.earliestAcceptableDepartureTime = earliestAcceptableDepartureTime < 0
                ? unreachedTime()
                : earliestAcceptableDepartureTime;
        this.iterationStep = iterationStep;
    }

    @Override
    public final int plusDuration(final int time, final int duration) {
        // It might seems strange to use minus int the add method, but
        // the "positive" direction in this class is backwards in time;
        // hence we need to subtract the board slack.
        return time - duration;
    }

    @Override
    public final int minusDuration(final int time, final int duration) {
        // It might seems strange to use plus int the subtract method, but
        // the "positive" direction in this class is backwards in time;
        // hence we need to add the board slack.
        return time + duration;
    }

    @Override
    public final int duration(final int timeA, final int timeB) {
        // When searching in reverse time A is > time B, so to
        // calculate the duration we need to swap A and B
        // compared with the normal forward search
        return timeA - timeB;
    }

    @Override
    public final int earliestBoardTime(int time) {
        // The boardSlack is NOT added here (as in a forward search) - it is added to the arrival time instead
        return time;
    }

    @Override
    public final int addBoardSlack(int time) {
        return plusDuration(time, boardSlackInSeconds);
    }

    @Override
    public final int removeBoardSlack(int time) {
        return minusDuration(time, boardSlackInSeconds);
    }

    @Override
    public final <T extends TripScheduleInfo> int latestArrivalTime(T onTrip, int stopPositionInPattern) {
        return plusDuration(onTrip.departure(stopPositionInPattern), boardSlackInSeconds);
    }

    @Override
    public final boolean exceedsTimeLimit(int time) {
        return isBest(earliestAcceptableDepartureTime, time);
    }

    @Override
    public final String exceedsTimeLimitReason() {
        return "The departure time exceeds the time limit, depart to early: " +
                TimeUtils.timeToStrLong(earliestAcceptableDepartureTime) + ".";
    }

    @Override
    public final boolean isBest(final int subject, final int candidate) {
        // The latest time is the best when searching in reverse
        return subject > candidate;
    }

    @Override
    public final int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return firstTransitBoardTime + accessLegDuration;
    }

    @Override
    public final int unreachedTime() {
        return Integer.MIN_VALUE;
    }

    @Override
    public final IntIterator rangeRaptorMinutes() {
        return oneIterationOnly()
                ? IntIterators.singleValueIterator(latestArrivalTime)
                : IntIterators.intIncIterator(
                        latestArrivalTime - searchWindowInSeconds,
                        latestArrivalTime,
                        iterationStep
                );
    }

    @Override
    public boolean oneIterationOnly() {
        return searchWindowInSeconds <= iterationStep;
    }

    @Override
    public final IntIterator patternStopIterator(int nStopsInPattern) {
        return IntIterators.intDecIterator(nStopsInPattern, 0);
    }

    @Override
    public final IntIterator patternStopIterator(int onTripStopPos, int nStopsInPattern) {
        return IntIterators.intDecIterator(onTripStopPos, 0);
    }

    @Override
    public final <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleAlightSearch<>(tripSearchBinarySearchThreshold, pattern, skipTripScheduleCallback);
    }

    @Override
    public final <T extends TripScheduleInfo> TripScheduleSearch<T> createExactTripSearch(
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleExactMatchSearch<>(
                createTripSearch(pattern, skipTripScheduleCallback),
                this,
                -iterationStep
        );
    }

    @Override
    public final <T extends TripScheduleInfo> PathMapper<T> createPathMapper() {
        return new ReversePathMapper<>(this);
    }
}
