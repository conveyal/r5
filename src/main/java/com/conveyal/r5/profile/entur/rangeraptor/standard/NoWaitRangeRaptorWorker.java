package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;


/**
 * This is a special edition of the StdRangeRaptorWorker witch remove all unnecessary waiting,
 * this allows us to compute optimistic minimum travel times from origin and to all other stops.
 * <p/>
 * The required boardSlack is honored.
 * <p/>
 * Used in combination with reverse search this can be used to calculate minimum time/transfers/cost
 * to reach destination.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class NoWaitRangeRaptorWorker<T extends TripScheduleInfo> extends StdRangeRaptorWorker<T> {

    private static final int NOT_SET = -1;

    private int onTripTimeShift;


    public NoWaitRangeRaptorWorker(SearchContext<T> context, StdWorkerState<T> state) {
        super(context, state);
    }

    protected final void prepareTransitForRoundAndPattern() {
        this.onTripTimeShift = NOT_SET;
    }

    protected int boardTime(final int earliestBoardTime) {
        onTripTimeShift = tripSearch().getCandidateTripTime() - earliestBoardTime;
        return earliestBoardTime;
    }

    protected int alightTime(final int stopPositionInPattern) {
        // In the normal case the arrivalTime is used, but in reverse search
        // the board slack is added; hence the calculator delegation
        return calculator().latestArrivalTime(onTrip(), stopPositionInPattern) - onTripTimeShift;
    }
}
