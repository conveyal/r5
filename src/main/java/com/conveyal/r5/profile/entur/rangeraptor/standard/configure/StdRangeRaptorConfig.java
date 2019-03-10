package com.conveyal.r5.profile.entur.rangeraptor.standard.configure;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.CalculateHeuristicWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.NoWaitRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.profile.entur.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimesOnlyStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.SimpleArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.debug.DebugStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.StdStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.Stops;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.path.EgressArrivalToPathAdapter;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopsCursor;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;


/**
 * The responsibility of this class is to wire different standard range raptor
 * worker configurations together based on the context passed into the class.
 * There is a factory (create) method for each legal configuration.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StdRangeRaptorConfig<T extends TripScheduleInfo> {
    private final SearchContext<T> ctx;
    private BestTimes bestTimes = null;
    private ArrivedAtDestinationCheck destinationCheck = null;
    private Stops<T> stops = null;
    private CalculateHeuristicWorkerState<T> heuristicWorkerState = null;


    public StdRangeRaptorConfig(SearchContext<T> context) {
        this.ctx = context;
    }

    public StdRangeRaptorWorker<T> createStandardSearch() {
        StdRangeRaptorWorker<T> worker = createWorker(stdStopArrivalsState());
        clear();
        return worker;
    }

    public StdRangeRaptorWorker<T> createBestTimeSearch() {
        StdRangeRaptorWorker<T> worker = createWorker(bestTimeStopArrivalsState());
        clear();
        return worker;
    }

    public StdRangeRaptorWorker<T> createStdHeuristicsSearch() {
        StdRangeRaptorWorker<T> worker = createWorker(heuristicArrivalsState());
        clear();
        return worker;
    }

    public NoWaitRangeRaptorWorker<T> createNoWaitHeuristicsSearch() {
        NoWaitRangeRaptorWorker<T> worker = createNoWaitWorker(heuristicArrivalsState());
        clear();
        return worker;
    }

    /**
     * @return state or null if not created
     * @deprecated TODO TGR - make the heuristic a consept, do not pass the entier state out to the ouside world
     */
    @Deprecated
    public CalculateHeuristicWorkerState<T> getHeuristicWorkerState() {
        return heuristicWorkerState;
    }


    /* private factory methods */

    /**
     * When creating a worker make sure not to reuse the temporary state. To make sure
     * this does not happen, just clear the state, before returning the worker.
     */
    private void clear() {
        bestTimes = null;
        destinationCheck = null;
        stops = null;
        heuristicWorkerState = null;
    }


    private StdRangeRaptorWorker<T> createWorker(StopArrivalsState<T> stopArrivalsState) {
        return new StdRangeRaptorWorker<>(ctx, workerState(stopArrivalsState));
    }

    private NoWaitRangeRaptorWorker<T> createNoWaitWorker(StopArrivalsState<T> stopArrivalsState) {
        return new NoWaitRangeRaptorWorker<>(ctx, workerState(stopArrivalsState));
    }

    private StdRangeRaptorWorkerState<T> workerState(StopArrivalsState<T> stopArrivalsState) {
        return new StdRangeRaptorWorkerState<>(ctx.calculator(), bestTimes(), stopArrivalsState, destinationCheck());
    }

    private BestTimesOnlyStopArrivalsState<T> bestTimeStopArrivalsState() {
        return new BestTimesOnlyStopArrivalsState<>(bestTimes());
    }

    /**
     * Create a Standard Range Raptor state for the given context. If debugging is enabled,
     * the stop arrival state is wrapped.
     */
    private StopArrivalsState<T> stdStopArrivalsState() {
        StdStopArrivalsState<T> state = new StdStopArrivalsState<>(stops(), destinationArrivalPaths());
        return wrapStopArrivalsStateWithDebugger(state);
    }

    private StopArrivalsState<T> wrapStopArrivalsStateWithDebugger(StopArrivalsState<T> state) {
        if (ctx.debugFactory().isDebugStopArrival()) {
            return new DebugStopArrivalsState<>(ctx.roundProvider(), ctx.debugFactory(), stopsCursor(), state);
        } else {
            return state;
        }
    }

    private CalculateHeuristicWorkerState<T> heuristicArrivalsState() {
        if (heuristicWorkerState == null) {
            heuristicWorkerState = new CalculateHeuristicWorkerState<>(ctx.nStops(), ctx.roundProvider(), bestTimes(), ctx.costCalculator(), ctx.lifeCycle());
        }
        return heuristicWorkerState;
    }

    private Stops<T> stops() {
        if (stops == null) {
            stops = new Stops<>(
                    ctx.nRounds(),
                    ctx.nStops(),
                    ctx.roundProvider()
            );
        }
        return stops;
    }

    private StopsCursor<T> stopsCursor() {
        // Always create new cursors
        return new StopsCursor<>(stops(), ctx.calculator());
    }

    private DestinationArrivalPaths<T> destinationArrivalPaths() {
        DestinationArrivalPaths<T> destinationArrivalPaths = new DestinationArrivalPaths<>(
                DestinationArrivalPaths.paretoComparatorWithoutCost(),
                ctx.calculator(),
                ctx.debugFactory(),
                ctx.lifeCycle()
        );

        // Add egressArrivals to stops and bind them to the destination arrival paths. The
        // adapter notify the destination on each new egress stop arrival.
        EgressArrivalToPathAdapter<T> pathsAdapter = new EgressArrivalToPathAdapter<>(
                destinationArrivalPaths,
                ctx.calculator(),
                stopsCursor(),
                ctx.lifeCycle(),
                ctx.debugFactory()
        );

        // Use the  adapter to play the role of the destination arrival check
        setDestinationCheck(pathsAdapter);

        stops().setupEgressStopStates(ctx.egressLegs(), pathsAdapter::add);

        return destinationArrivalPaths;
    }

    private BestTimes bestTimes() {
        // Cache best times; request scope
        if (bestTimes == null) {
            bestTimes = new BestTimes(ctx.nStops(), ctx.calculator(), ctx.lifeCycle());
        }
        return bestTimes;
    }

    private ArrivedAtDestinationCheck destinationCheck() {
        // Cache best times; request scope
        if (destinationCheck == null) {
            setDestinationCheck(simpleDestinationCheck());
        }
        return destinationCheck;
    }

    private void setDestinationCheck(ArrivedAtDestinationCheck check) {
        // Cache best times; request scope
        if (destinationCheck != null) {
            throw new IllegalStateException(
                    "ArrivedAtDestinationCheck is alredy initialized: " + destinationCheck.getClass().getSimpleName()
            );
        }
        destinationCheck = check;
    }

    private SimpleArrivedAtDestinationCheck simpleDestinationCheck() {
        return new SimpleArrivedAtDestinationCheck(ctx.egressStops(), bestTimes());
    }
}
