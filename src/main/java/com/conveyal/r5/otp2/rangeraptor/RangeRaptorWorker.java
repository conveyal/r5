package com.conveyal.r5.otp2.rangeraptor;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TransitDataProvider;
import com.conveyal.r5.otp2.api.transit.TripPatternInfo;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.Worker;
import com.conveyal.r5.otp2.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.otp2.rangeraptor.transit.RoundTracker;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.rangeraptor.transit.TripScheduleBoardSearch;
import com.conveyal.r5.otp2.rangeraptor.transit.TripScheduleSearch;
import com.conveyal.r5.otp2.rangeraptor.workerlifecycle.LifeCycleEventPublisher;
import com.conveyal.r5.otp2.util.AvgTimer;

import java.util.Collection;
import java.util.Iterator;


/**
 * The algorithm used herein is described in
 * <p>
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 * Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 * Record 2653 (2017). doi:10.3141/2653-06.
 * <p>
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 * http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 * <p>
 * This version do support the following features:
 * <ul>
 *     <li>Raptor (R)
 *     <li>Range Raptor (RR)
 *     <li>Multi-criteria pareto optimal Range Raptor (McRR)
 *     <li>Reverse search in combination with R and RR
 * </ul>
 * This version do NOT support the following features:
 * <ul>
 *     <li>Frequency routes, supported by the original code using Monte Carlo methods (generating randomized schedules)
 * </ul>
 * <p>
 * This class originated as a rewrite of Conveyals RAPTOR code: https://github.com/conveyal/r5.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
@SuppressWarnings("Duplicates")
public final class RangeRaptorWorker<T extends TripScheduleInfo, S extends WorkerState<T>> implements Worker<T> {


    private final TransitRoutingStrategy<T> transitWorker;

    /**
     * The RangeRaptor state - we delegate keeping track of state to the state object,
     * this allow the worker implementation to focus on the algorithm, while
     * the state keep track of the result.
     * <p/>
     * This also allow us to try out different strategies for storing the result in memory.
     * For a long time we had a state witch stored all data as int arrays in addition to the
     * current object-oriented approach. There were no performance differences(=> GC is not
     * the bottle neck), so we dropped the integer array implementation.
     */
    private final S state;

    /**
     * The round tracker keep track for the current Raptor round, and abort the search if the
     * round max limit is reached.
     */
    private final RoundTracker roundTracker;

    private final TransitDataProvider<T> transitData;

    private final TransitCalculator calculator;

    private final WorkerPerformanceTimers timers;

    private final Collection<TransferLeg> accessLegs;

    private boolean matchBoardingAlightExactInFirstRound;

    /**
     * The life cycle is used to publish life cycle events to everyone who
     * listen.
     */
    private final LifeCycleEventPublisher lifeCycle;


    public RangeRaptorWorker(
            S state,
            TransitRoutingStrategy<T> transitWorker,
            TransitDataProvider<T> transitData,
            Collection<TransferLeg> accessLegs,
            RoundProvider roundProvider,
            TransitCalculator calculator,
            LifeCycleEventPublisher lifeCyclePublisher,
            WorkerPerformanceTimers timers,
            boolean waitAtBeginningEnabled
    ) {
        this.transitWorker = transitWorker;
        this.state = state;
        this.transitData = transitData;
        this.calculator = calculator;
        this.timers = timers;
        this.accessLegs = accessLegs;
        // We do a cast here to avoid exposing the round tracker  and the life cycle publisher to "everyone"
        // by providing access to it in the context.
        this.roundTracker = (RoundTracker) roundProvider;
        this.lifeCycle = lifeCyclePublisher;
        this.matchBoardingAlightExactInFirstRound = !waitAtBeginningEnabled;
    }

    /**
     * For each iteration (minute), calculate the minimum travel time to each transit stop in seconds.
     * <p/>
     * Run the scheduled search, round 0 is the street search
     * <p/>
     * We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
     * “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
     *
     * @return a unique set of paths
     */
    @Override
    final public Collection<Path<T>> route() {
        timerRoute().time(() -> {
            timerSetup(transitData::setup);

            // The main outer loop iterates backward over all minutes in the departure times window.
            // Ergo, we re-use the arrival times found in searches that have already occurred that
            // depart later, because the arrival time given departure at time t is upper-bounded by
            // the arrival time given departure at minute t + 1.
            final IntIterator it = calculator.rangeRaptorMinutes();
            while (it.hasNext()) {
                // Run the raptor search for this particular iteration departure time
                timerRouteByMinute(() -> runRaptorForMinute(it.next()));
            }
        });
        return state.extractPaths();
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param iterationDepartureTime When this search departs.
     */
    private void runRaptorForMinute(int iterationDepartureTime) {
        lifeCycle.setupIteration(iterationDepartureTime);

        doTransfersForAccessLegs(iterationDepartureTime);

        while (hasMoreRounds()) {
            lifeCycle.prepareForNextRound();

            // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
            // as that will be rare and complicates the code
            timerByMinuteScheduleSearch().time(this::findAllTransitForRound);

            timerByMinuteTransfers().time(this::transfersForRound);

            lifeCycle.roundComplete(state.isDestinationReachedInCurrentRound());
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here, the next iteration will modify the state, so we need to make
        // protective copies of any information we want to retain.
        lifeCycle.iterationComplete();
    }


    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute.
     * <p/>
     * This method is protected to allow reverce search to override it.
     */
    private void doTransfersForAccessLegs(int iterationDepartureTime) {
        for (TransferLeg it : accessLegs) {
            state.setInitialTimeForIteration(it, iterationDepartureTime);
        }
    }

    /**
     * Check if the RangeRaptor should continue with a new round.
     */
    private boolean hasMoreRounds() {
        return roundTracker.hasMoreRounds() && state.isNewRoundAvailable();
    }

    /**
     * Perform a scheduled search
     */
    private void findAllTransitForRound() {
        IntIterator stops = state.stopsTouchedPreviousRound();
        Iterator<? extends TripPatternInfo<T>> patternIterator = transitData.patternIterator(stops);

        while (patternIterator.hasNext()) {
            TripPatternInfo<T> pattern = patternIterator.next();
            TripScheduleSearch<T> tripSearch = createTripSearch(pattern);

            transitWorker.prepareForTransitWith(pattern, tripSearch);

            performTransitForRoundAndEachStopInPattern(pattern);
        }
        lifeCycle.transitsForRoundComplete();
    }

    /**
     * Iterate over given pattern and calculate transit for each stop.
     * <p/>
     * This is protected to allow reverse search to override and step backwards.
     */
    private void performTransitForRoundAndEachStopInPattern(final TripPatternInfo<T> pattern) {
        IntIterator it = calculator.patternStopIterator(pattern.numberOfStopsInPattern());
        while (it.hasNext()) {
            transitWorker.routeTransitAtStop(it.next());
        }
    }

    private void transfersForRound() {
        IntIterator it = state.stopsTouchedByTransitCurrentRound();

        while (it.hasNext()) {
            final int fromStop = it.next();
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            state.transferToStops(fromStop, transitData.getTransfers(fromStop));
        }
        lifeCycle.transfersForRoundComplete();
    }

    /**
     * Create a trip search using {@link TripScheduleBoardSearch}.
     * <p/>
     * This is protected to allow reverse search to override and create a alight search instead.
     */
    private TripScheduleSearch<T> createTripSearch(TripPatternInfo<T> pattern) {
        if(matchBoardingAlightExactInFirstRound && roundTracker.round() == 1) {
            return calculator.createExactTripSearch(pattern, this::skipTripSchedule);
        }
        else {
            return calculator.createTripSearch(pattern, this::skipTripSchedule);
        }
    }

    /**
     * Skip trips NOT running on the day of the search and skip frequency trips
     */
    private boolean skipTripSchedule(T trip) {
        return !transitData.isTripScheduleInService(trip);
    }

    // Track time spent, measure performance
    // TODO TGR - Replace by performance tests
    private void timerSetup(Runnable setup) { timers.timerSetup(setup); }
    private AvgTimer timerRoute() { return timers.timerRoute(); }
    private void timerRouteByMinute(Runnable routeByMinute) { timers.timerRouteByMinute(routeByMinute); }
    private AvgTimer timerByMinuteScheduleSearch() { return timers.timerByMinuteScheduleSearch(); }
    private AvgTimer timerByMinuteTransfers() { return timers.timerByMinuteTransfers(); }
}
