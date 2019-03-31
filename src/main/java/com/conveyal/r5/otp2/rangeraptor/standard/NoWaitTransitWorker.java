package com.conveyal.r5.otp2.rangeraptor.standard;

import com.conveyal.r5.otp2.api.transit.TripPatternInfo;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.PerformTransitStrategy;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.rangeraptor.transit.TripScheduleSearch;


/**
 * The purpose of this class is to implement the "Standard" specific functionality of the worker with
 * NO WAIT TIME between transfer and transit, except the boardSlack.
 * <p/>
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class NoWaitTransitWorker<T extends TripScheduleInfo> implements PerformTransitStrategy<T> {

    private static final int NOT_SET = -1;

    private final StdWorkerState<T> state;
    private final TransitCalculator calculator;

    private int onTripIndex;
    private int onTripBoardTime;
    private int onTripBoardStop;
    private T onTrip;
    private int onTripTimeShift;
    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;

    public NoWaitTransitWorker(
            StdWorkerState<T> state,
            TransitCalculator calculator
    ) {
        this.state = state;
        this.calculator = calculator;
    }

    @Override
    public void prepareForTransitWith(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
        this.onTripIndex = NOT_SET;
        this.onTripBoardTime = NOT_SET;
        this.onTripBoardStop = NOT_SET;
        this.onTrip = null;
        this.onTripTimeShift = NOT_SET;
    }

    @Override
    public void routeTransitAtStop(int stopPositionInPattern) {
        int stop = pattern.stopIndex(stopPositionInPattern);

        // attempt to alight if we're on board, done above the board search so that we don't check for alighting
        // when boarding
        if (onTripIndex != NOT_SET) {
            state.transitToStop(
                    stop,
                    alightTime(onTrip, stopPositionInPattern),
                    onTripBoardStop,
                    onTripBoardTime,
                    onTrip
            );
        }

        // Don't attempt to board if this stop was not reached in the last round.
        // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
        if (state.isStopReachedInPreviousRound(stop)) {
            int earliestBoardTime = calculator.earliestBoardTime(state.bestTimePreviousRound(stop));

            // check if we can back up to an earlier trip due to this stop being reached earlier
            boolean found = tripSearch.search(earliestBoardTime, stopPositionInPattern, onTripIndex);

            if (found) {
                onTripIndex = tripSearch.getCandidateTripIndex();
                onTrip = tripSearch.getCandidateTrip();
                onTripBoardTime = earliestBoardTime;
                onTripBoardStop = stop;
                onTripTimeShift = tripSearch.getCandidateTripTime() - earliestBoardTime;
            }
        }
    }

    public int alightTime(final T trip, final int stopPositionInPattern) {
        // In the normal case the arrivalTime is used, but in reverse search
        // the board slack is added; hence the calculator delegation
        return calculator.latestArrivalTime(trip, stopPositionInPattern) - onTripTimeShift;
    }
}
