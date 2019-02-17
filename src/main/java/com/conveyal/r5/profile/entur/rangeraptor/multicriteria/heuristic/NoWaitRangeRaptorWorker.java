package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;


/**
 * This is a special edition of the StdRangeRaptorWorker witch remove all unnecessary waiting,
 * this allows us to compute optimistic minimum travel times from origin and to all other stops.
 * <p/>
 * The requiered boardSlack is of cause honored.
 * <p/>
 * Used in combination with reverse search this can be used to calculate minimum time/transfers/cost
 * to reach destination.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class NoWaitRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<T, StdWorkerState<T>> {

    private static final int NOT_SET = -1;

    private int onTripIndex;
    private int onTripBoardTime;
    private int onTripBoardStop;
    private int onTripTimeShift;
    private T onTrip;
    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;


    public NoWaitRangeRaptorWorker(SearchContext<T> context, StdWorkerState<T> state) {
        super(context, state);
    }

    @Override
    protected final void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
        this.onTripIndex = NOT_SET;
        this.onTripBoardTime = 0;
        this.onTripBoardStop = -1;
        this.onTrip = null;
    }

    @Override
    protected final void performTransitForRoundAndPatternAtStop(int stopPositionInPattern) {
        int stop = pattern.stopIndex(stopPositionInPattern);

        // attempt to alight if we're on board, done above the board search so that we don't check for alighting
        // when boarding
        if (onTripIndex != -1) {
            state.transitToStop(
                    stop,
                    // In the normal case the arrivalTime is used,
                    // but in reverse search the board slack is added; hence the calculator delegation
                    calculator().latestArrivalTime(onTrip, stopPositionInPattern) - onTripTimeShift,
                    onTrip,
                    onTripBoardStop,
                    onTripBoardTime
            );
        }

        // Don't attempt to board if this stop was not reached in the last round.
        // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
        if (state.isStopReachedInPreviousRound(stop)) {
            int earliestBoardTime = calculator().earliestBoardTime(state.bestTimePreviousRound(stop));

            // check if we can back up to an earlier trip due to this stop being reached earlier
            boolean found = tripSearch.search(earliestBoardTime, stopPositionInPattern, onTripIndex);

            if (found) {
                onTripIndex = tripSearch.getCandidateTripIndex();
                onTrip = tripSearch.getCandidateTrip();
                onTripBoardTime = earliestBoardTime;
                onTripTimeShift = tripSearch.getCandidateTripTime() - earliestBoardTime;
                onTripBoardStop = stop;
            }
        }
    }
}
