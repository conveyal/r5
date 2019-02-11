package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.path.ReversePathMapper;
import com.conveyal.r5.profile.entur.util.IntIterators;

import java.util.function.Function;

/**
 * A calculator that will take you back in time not forward, this is the
 * basic logic to implement a reveres search.
 */
final class ReverseSearchTransitCalculator implements TransitCalculator {
    private final int tripSearchBinarySearchThreshold;
    private final int boardSlackInSeconds;
    private final int fromTime;
    private final int toTime;
    private final int iterationStep;



    ReverseSearchTransitCalculator(RangeRaptorRequest<?> r, TuningParameters t) {
        // The request is already modified to search backwards, so 'fromTime()'
        // goes with destination and 'toTime()' match origin.
        this(
            t.scheduledTripBinarySearchThreshold(),
            r.boardSlackInSeconds(),
            r.fromTime(),
            r.toTime(),
            t.iterationDepartureStepInSeconds()
        );
    }

    private ReverseSearchTransitCalculator(
            int binaryTripSearchThreshold,
            int boardSlackInSeconds,
            int fromTime,
            int toTime,
            int iterationStep
    ) {
        this.tripSearchBinarySearchThreshold = binaryTripSearchThreshold;
        this.boardSlackInSeconds = boardSlackInSeconds;
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.iterationStep = iterationStep;
    }

    @Override
    public final int add(final int time, final int delta) {
        // It might seems strange to use minus int the add method, but
        // the "positive" direction in this class is backwards in time;
        // hence we need to subtract the board slack.
        return time - delta;
    }

    @Override
    public final int sub(final int time, final int delta) {
        // It might seems strange to use plus int the subtract method, but
        // the "positive" direction in this class is backwards in time;
        // hence we need to add the board slack.
        return time + delta;
    }

    @Override
    public int earliestBoardTime(int time) {
        // The boardSlack is NOT added here - as in the normal forward search
        return time;
    }

    @Override
    public <T extends TripScheduleInfo> int latestArrivalTime(T onTrip, int stopPositionInPattern) {
        return add(onTrip.departure(stopPositionInPattern), boardSlackInSeconds);
    }

    @Override
    public final boolean isBest(final int subject, final int candidate) {
        // The latest time is the best when searching in reverse
        return subject > candidate;
    }

    @Override
    public int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return firstTransitBoardTime + accessLegDuration;
    }

    @Override
    public final int unreachedTime() {
        return Integer.MIN_VALUE;
    }

    @Override
    public final IntIterator rangeRaptorMinutes() {
        return IntIterators.intIncIterator(toTime, fromTime, iterationStep);
    }

    @Override
    public IntIterator patternStopIterator(int nStopsInPattern, int startStopPos) {
        return IntIterators.intDecIterator(startStopPos, -1);
    }

    @Override
    public <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleAlightSearch<>(tripSearchBinarySearchThreshold, pattern, skipTripScheduleCallback);
    }


    @Override
    public <T extends TripScheduleInfo> PathMapper<T> createPathMapper() {
        return new ReversePathMapper<>(this.boardSlackInSeconds);
    }
}
