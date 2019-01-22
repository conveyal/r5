package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.IntIterators;

import java.util.function.Function;

final class ForwardSearchTransitCalculator extends AbstractTransitCalculator {
    private final int fromTime;
    private final int iterationStep;

    ForwardSearchTransitCalculator(RangeRaptorRequest<?> request, int rrIterationStep) {
        this(request.boardSlackInSeconds(), request.fromTime(), request.toTime(), rrIterationStep);
    }

    ForwardSearchTransitCalculator(int boardSlackInSeconds, int fromTime, int toTime, int iterationStep) {
        super(boardSlackInSeconds, toTime);
        this.fromTime = fromTime;
        this.iterationStep = iterationStep;
    }

    @Override
    public final int add(final int time, final int delta) {
        return time + delta;
    }

    @Override
    public final int sub(final int time, final int delta) {
        return time - delta;
    }

    @Override
    public final boolean isBest(final int subject, final int candidate) {
        return subject < candidate;
    }

    @Override
    public final int unreachedTime() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAForwardSearch() {
        return true;
    }

    @Override
    public final IntIterator rangeRaptorMinutes() {
        return IntIterators.intDecIterator(maxTimeLimit()-iterationStep, fromTime-1, iterationStep);
    }

    @Override
    public IntIterator patternStopIterator(int numberOfStopsInPattern) {
        return IntIterators.intIncIterator(0, numberOfStopsInPattern);
    }

    @Override
    public <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            int scheduledTripBinarySearchThreshold,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleBoardSearch<>(scheduledTripBinarySearchThreshold, pattern, skipTripScheduleCallback);
    }

}
