package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleBoardSearch;

import java.util.Collection;


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
@SuppressWarnings("Duplicates")
public class RangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<RangeRaptorWorkerState<T>, T> {

    private static final int NOT_SET = -1;

    private int onTrip;
    private int boardTime;
    private int boardStop;
    private T boardTrip;
    private TripPatternInfo<T> pattern;
    private TripScheduleBoardSearch<T> tripSearch;


    public RangeRaptorWorker(TransitDataProvider<T> transitData, int nRounds, RangeRaptorRequest<T> request, WorkerPerformanceTimers timers) {
        super(
                transitData,
                new RangeRaptorWorkerState<>(nRounds, transitData.numberOfStops(), request),
                request,
                timers
        );
    }

    @Override
    protected Collection<Path<T>> paths() {
        return state.paths();
    }

    /**
     * Create the optimal path to each stop in the transit network, based on the given McRaptorState.
     */
    @Override
    protected void addPathsForCurrentIteration() {
        state.addPathsForCurrentIteration();
    }


    @Override
    protected void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleBoardSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
        this.onTrip = NOT_SET;
        this.boardTime = 0;
        this.boardStop = -1;
        this.boardTrip = null;

    }

    @Override
    protected void performTransitForRoundAndPatternAtStop(int stopPositionInPattern) {
        int stop = pattern.stopIndex(stopPositionInPattern);

        // attempt to alight if we're on board, done above the board search so that we don't check for alighting
        // when boarding
        if (onTrip != -1) {
            state.transitToStop(
                    stop,
                    boardTrip.arrival(stopPositionInPattern),
                    boardTrip,
                    boardStop,
                    boardTime
            );
        }

        // Don't attempt to board if this stop was not reached in the last round.
        // Allow to reboard the same pattern - a pattern may loop and visit the same stop twice
        if (state.isStopReachedInPreviousRound(stop)) {
            int earliestBoardTime = earliestBoardTime(state.bestTimePreviousRound(stop));
            int tripIndexUpperBound = (onTrip == -1 ? pattern.numberOfTripSchedules() : onTrip);

            // check if we can back up to an earlier trip due to this stop being reached earlier
            boolean found = tripSearch.search(tripIndexUpperBound, earliestBoardTime, stopPositionInPattern);

            if (found) {
                onTrip = tripSearch.candidateTripIndex;
                boardTrip = tripSearch.candidateTrip;
                boardTime = boardTrip.departure(stopPositionInPattern);
                boardStop = stop;
            }
        }
    }
}
