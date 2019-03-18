package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;


/**
 * TODO TGR
 * RaptorWorker is fast, but FastRaptorWorker is knock-your-socks-off fast, and also more maintainable.
 * It is also simpler, as it only focuses on the transit network; see the Propagater class for the methods that extend
 * the travel times from the final transit stop of a trip out to the individual targets.
 * <p>
 * The algorithm used herein is described in
 * <p>
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 * Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 * Record 2653 (2017). doi:10.3141/2653-06.
 * <p>
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 * http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 * <p>
 * There is currently no support for saving paths.
 * <p>
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing computation by a
 * generic function-execution service like AWS Lambda. The gains in efficiency were significant enough that this is now
 * the way we do all analysis work. This system also accounts for pure-frequency routes by using Monte Carlo methods
 * (generating randomized schedules).
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StdRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<T, StdWorkerState<T>> {

    private static final int NOT_SET = -1;

    private int onTripIndex;
    private int onTripBoardTime;
    private int onTripBoardStop;
    private T onTrip;
    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;


    public StdRangeRaptorWorker(SearchContext<T> context, StdWorkerState<T> state) {
        super(context, state);
    }

    TripScheduleSearch<T> tripSearch() {
        return tripSearch;
    }

    T onTrip() {
        return onTrip;
    }

    @Override
    protected final void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
        this.onTripIndex = NOT_SET;
        this.onTripBoardTime = 0;
        this.onTripBoardStop = -1;
        this.onTrip = null;
        prepareTransitForRoundAndPattern();
    }

    protected void prepareTransitForRoundAndPattern() { }

    @Override
    protected final void performTransitForRoundAndPatternAtStop(int stopPositionInPattern) {
        int stop = pattern.stopIndex(stopPositionInPattern);

        // attempt to alight if we're on board, done above the board search so that we don't check for alighting
        // when boarding
        if (onTripIndex != NOT_SET) {
            state.transitToStop(
                    stop,
                    alightTime(stopPositionInPattern),
                    onTripBoardStop,
                    onTripBoardTime,
                    onTrip
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
                onTripBoardTime = boardTime(earliestBoardTime);
                onTripBoardStop = stop;
            }
        }
    }

    protected int boardTime(final int earliestBoardTime) {
        return tripSearch.getCandidateTripTime();
    }


    protected int alightTime(final int stopPositionInPattern) {
        // In the normal case the arrivalTime is used,
        // but in reverse search the board slack is added; hence the calculator delegation
        return calculator().latestArrivalTime(onTrip, stopPositionInPattern);
    }
}
