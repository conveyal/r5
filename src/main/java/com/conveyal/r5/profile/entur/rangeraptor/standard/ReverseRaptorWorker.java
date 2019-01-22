package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleAlightSearch;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;


/**
 * TODO TGR
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class ReverseRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<T, RangeRaptorWorkerState<T>> {

    private static final int NOT_SET = -1;

    private int onTripIndex;
    private int onTripAlightTime;
    private int onTripAlightStop;
    private T onTrip;
    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;

    public ReverseRaptorWorker(SearchContext<T> context) {
        super(
                context,
                new RangeRaptorWorkerState<>(
                        nRounds(context.tuningParameters()),
                        context.transit().numberOfStops(),
                        context.calculator(),
                        context.request()
                )
        );
    }

    /**
     * Override the default method to traverse a pattern in the reverse direction.
     */
    @Override
    protected void performTransitForRoundAndEachStopInPattern(final TripPatternInfo<T> pattern) {
        for (int pos = pattern.numberOfStopsInPattern()-1; pos >= 0; --pos) {
            performTransitForRoundAndPatternAtStop(pos);
        }
    }

    @Override
    protected TripScheduleSearch<T> createTripSearch(TripPatternInfo<T> pattern) {
        return new TripScheduleAlightSearch<>(
                tuningParameters().scheduledTripBinarySearchThreshold(),
                pattern,
                this::skipTripSchedule
        );
    }

    @Override
    protected void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
        this.onTripIndex = NOT_SET;
        this.onTripAlightTime = 0;
        this.onTripAlightStop = -1;
        this.onTrip = null;

    }

    @Override
    protected void performTransitForRoundAndPatternAtStop(int stopPositionInPattern) {
        int stop = pattern.stopIndex(stopPositionInPattern);

        // attempt to alight if we're on board, done above the board search so that we don't check for alighting
        // when boarding
        if (onTripIndex != -1) {
            state.transitToStop(
                    stop,
                    onTrip.departure(stopPositionInPattern),
                    onTrip,
                    onTripAlightStop,
                    onTripAlightTime
            );
        }

        // Don't attempt to board if this stop was not reached in the last round.
        // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
        if (state.isStopReachedInPreviousRound(stop)) {
            int earliestBoardTime = calculator().addBoardSlack(state.bestTimePreviousRound(stop));

            // check if we can back up to an earlier trip due to this stop being reached earlier
            boolean found = tripSearch.search(earliestBoardTime, stopPositionInPattern, onTripIndex);

            if (found) {
                onTripIndex = tripSearch.getCandidateTripIndex();
                onTrip = tripSearch.getCandidateTrip();
                onTripAlightTime = onTrip.arrival(stopPositionInPattern);
                onTripAlightStop = stop;
            }
        }
    }
}
