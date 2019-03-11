package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.path.ForwardPathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.util.IntIterators;
import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.function.Function;

final class ForwardSearchTransitCalculator implements TransitCalculator {
    private final int tripSearchBinarySearchThreshold;
    private final int boardSlackInSeconds;
    private final int earliestDepartureTime;
    private final int searchWindowSizeInSeconds;
    private final int latestAcceptableArrivalTime;
    private final int iterationStep;

    ForwardSearchTransitCalculator(RangeRaptorRequest<?> r, TuningParameters t) {
        this(
                t.scheduledTripBinarySearchThreshold(),
                r.boardSlackInSeconds(),
                r.earliestDepartureTime(),
                r.searchWindowInSeconds(),
                r.latestArrivalTime(),
                t.iterationDepartureStepInSeconds()
        );
    }

    ForwardSearchTransitCalculator(
            int tripSearchBinarySearchThreshold,
            int boardSlackInSeconds,
            int earliestDepartureTime,
            int searchWindowSizeInSeconds,
            int latestAcceptableArrivalTime,
            int iterationStep
    ) {
        this.tripSearchBinarySearchThreshold = tripSearchBinarySearchThreshold;
        this.boardSlackInSeconds = boardSlackInSeconds;
        this.earliestDepartureTime = earliestDepartureTime;
        this.searchWindowSizeInSeconds = searchWindowSizeInSeconds;
        this.latestAcceptableArrivalTime = latestAcceptableArrivalTime < 0
                ? unreachedTime()
                : latestAcceptableArrivalTime;
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
    public final int earliestBoardTime(int time) {
        // When searching forward we must add the board slack before we board.
        return addBoardSlack(time);
    }

    @Override
    public final int addBoardSlack(int time) {
        return time + boardSlackInSeconds;
    }

    @Override
    public final int removeBoardSlack(int time) {
        return time - boardSlackInSeconds;
    }

    @Override
    public <T extends TripScheduleInfo> int latestArrivalTime(T onTrip, int stopPositionInPattern) {
        return onTrip.arrival(stopPositionInPattern);
    }

    @Override
    public final boolean exceedsTimeLimit(int time) {
        return isBest(latestAcceptableArrivalTime, time);
    }

    @Override
    public String exceedsTimeLimitReason() {
        return "The arrival time exceeds the time limit, arrive to late: " +
                TimeUtils.timeToStrLong(latestAcceptableArrivalTime) + ".";
    }

    @Override
    public final boolean isBest(final int subject, final int candidate) {
        return subject < candidate;
    }

    @Override
    public final int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return firstTransitBoardTime - (boardSlackInSeconds + accessLegDuration);
    }

    @Override
    public final int unreachedTime() {
        return Integer.MAX_VALUE;
    }

    @Override
    public final IntIterator rangeRaptorMinutes() {
        return IntIterators.intDecIterator(
                earliestDepartureTime + searchWindowSizeInSeconds,
                earliestDepartureTime,
                iterationStep
        );
    }

    @Override
    public final IntIterator patternStopIterator(int nStopsInPattern) {
        return IntIterators.intIncIterator(0, nStopsInPattern);
    }

    @Override
    public final IntIterator patternStopIterator(int onTripStopPos, int nStopsInPattern) {
        // We need to add one, because the input trip is the boarded stop position
        return IntIterators.intIncIterator(onTripStopPos + 1, nStopsInPattern);
    }

    @Override
    public final <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    ) {
        return new TripScheduleBoardSearch<>(tripSearchBinarySearchThreshold, pattern, skipTripScheduleCallback);
    }

    @Override
    public final <T extends TripScheduleInfo> PathMapper<T> createPathMapper() {
        return new ForwardPathMapper<>(this);
    }
}
