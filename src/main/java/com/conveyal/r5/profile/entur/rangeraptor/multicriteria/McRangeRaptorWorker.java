package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.AbstractRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;

import java.util.Collection;


/**
 * The purpose TODO
 * <p>
 * The algorithm used herein is described in TODO
 * <p>
 * This class originated as a rewrite of our RAPTOR code that TODO
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class McRangeRaptorWorker<T extends TripScheduleInfo> extends AbstractRangeRaptorWorker<McRangeRaptorWorkerState<T>, T> {

    private TripPatternInfo<T> pattern;
    private TripScheduleSearch<T> tripSearch;


    public McRangeRaptorWorker(
            TuningParameters tuningParameters,
            TransitDataProvider<T> transitData,
            RangeRaptorRequest<T> request,
            WorkerPerformanceTimers timers
    ) {
        super(
                tuningParameters,
                transitData,
                new McRangeRaptorWorkerState<>(
                        nRounds(tuningParameters),
                        transitData.numberOfStops(),
                        request.egressLegs,
                        new TransitCalculator(request),
                        new DebugHandlerFactory<>(request.debug)
                ),
                request,
                timers
        );
    }

    @Override
    protected Collection<Path<T>> paths() {
        return state.extractPaths();
    }

    @Override
    protected void addPathsForCurrentIteration() {
        // NOOP
    }

    @Override
    protected void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch) {
        this.pattern = pattern;
        this.tripSearch = tripSearch;
    }

    /**
     * Perform a scheduled search
     */
    protected void performTransitForRoundAndPatternAtStop(int boardStopPositionInPattern) {
        final int nPatternStops = pattern.numberOfStopsInPattern();
        int boardStopIndex = pattern.stopIndex(boardStopPositionInPattern);

        for (AbstractStopArrival<T> boardStop : state.listStopArrivalsPreviousRound(boardStopIndex)) {

            int earliestBoardTime = earliestBoardTime(boardStop.arrivalTime());
            boolean found = tripSearch.search(earliestBoardTime, boardStopPositionInPattern);

            if (found) {
                for (int alightStopPos = boardStopPositionInPattern + 1; alightStopPos < nPatternStops; alightStopPos++) {
                    int alightStopIndex = pattern.stopIndex(alightStopPos);

                    T trip = tripSearch.getCandidateTrip();

                    state.transitToStop(
                            boardStop,
                            alightStopIndex,
                            trip.arrival(alightStopPos),
                            trip.departure(boardStopPositionInPattern),
                            trip
                    );
                }
            }
        }
    }
}
