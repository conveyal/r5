package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.IntIterators;

import java.util.function.Function;

import static com.conveyal.r5.profile.entur.util.IntIterators.singleValueIterator;

final class BackwardSearchTransitCalculator extends AbstractTransitCalculator {
    private final int latestAcceptableArrivalTime;

    BackwardSearchTransitCalculator(int boardSlackInSeconds, int latestDestArrivalTime, int earliestOriginDepartureTime) {
        super(boardSlackInSeconds, earliestOriginDepartureTime);
        this.latestAcceptableArrivalTime = latestDestArrivalTime;
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
    public final boolean isBest(final int subject, final int candidate) {
        // The latest time is the best when searching in reverse
        return subject > candidate;
    }

    @Override
    public final int unreachedTime() {
        return 0;
    }

    @Override
    public boolean isAForwardSearch() {
        return false;
    }

    @Override
    public final IntIterator rangeRaptorMinutes() {
        return singleValueIterator(latestAcceptableArrivalTime);
    }

    @Override
    public IntIterator patternStopIterator(TripPatternInfo<?> pattern) {
        return IntIterators.intDecIterator(pattern.numberOfStopsInPattern()-1, -1);
    }

    @Override
    public <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            int scheduledTripBinarySearchThreshold,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleAlightSearch<>(scheduledTripBinarySearchThreshold, pattern, skipTripScheduleCallback);
    }

}
