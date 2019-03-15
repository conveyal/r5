package com.conveyal.r5.profile.entur.rangeraptor.standard.configure;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.profile.entur.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.BestNumberOfTransfers;
import com.conveyal.r5.profile.entur.rangeraptor.standard.NoWaitRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimesOnlyStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.SimpleArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.SimpleBestNumberOfTransfers;
import com.conveyal.r5.profile.entur.rangeraptor.standard.debug.DebugStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics.HeuristicsAdapter;
import com.conveyal.r5.profile.entur.rangeraptor.standard.stoparrivals.StdStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.stoparrivals.Stops;
import com.conveyal.r5.profile.entur.rangeraptor.standard.stoparrivals.path.EgressArrivalToPathAdapter;
import com.conveyal.r5.profile.entur.rangeraptor.standard.stoparrivals.view.StopsCursor;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;


/**
 * The responsibility of this class is to wire different standard range raptor
 * worker configurations together based on the context passed into the class.
 * There is a factory (create) method for each legal configuration.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StdRangeRaptorConfig<T extends TripScheduleInfo> {
    private final SearchContext<T> ctx;

    private boolean workerCreated = false;
    private BestTimes bestTimes = null;
    private Stops<T> stops = null;
    private ArrivedAtDestinationCheck destinationCheck = null;
    private BestNumberOfTransfers bestNumberOfTransfers = null;
    private Heuristics heuristics = null;


    private StdRangeRaptorConfig(SearchContext<T> context) {
        this.ctx = context;
    }

    public static <T extends TripScheduleInfo> StdRangeRaptorWorker<T> createSearch(SearchContext<T> context) {
        return new StdRangeRaptorConfig<>(context).createSearch(false);
    }

    public static <T extends TripScheduleInfo> HeuristicSearch<T> createHeuristicSearch(SearchContext<T> ctx) {
        return new StdRangeRaptorConfig<>(ctx).createHeuristicSearch();
    }


    /* private factory methods */

    private StdRangeRaptorWorker<T> createSearch(boolean includeHeuristics) {
        new VerifyRequestIsValid(ctx).verify();

        switch (ctx.request().profile()) {
            case STANDARD:
                return createWorker(includeHeuristics, stdStopArrivalsState());
            case BEST_TIME:
                return createWorker(includeHeuristics, bestTimeStopArrivalsState());
            case NO_WAIT_STD:
                return createNoWaitWorker(includeHeuristics, stdStopArrivalsState());
            case NO_WAIT_BEST_TIME:
                return createNoWaitWorker(includeHeuristics, bestTimeStopArrivalsState());
        }
        throw new IllegalArgumentException(ctx.request().profile().toString());
    }

    private HeuristicSearch<T> createHeuristicSearch() {
        return new HeuristicSearch<>(createSearch(true), heuristics);
    }

    private StdRangeRaptorWorker<T> createWorker(boolean includeHeuristics, StopArrivalsState<T> stopArrivalsState) {
        assertOnlyOneWorkerIsCreated();
        StdRangeRaptorWorkerState<T> workerState = workerState(stopArrivalsState);
        createHeuristics(includeHeuristics);
        return new StdRangeRaptorWorker<>(ctx, workerState);
    }

    private NoWaitRangeRaptorWorker<T> createNoWaitWorker(boolean includeHeuristics, StopArrivalsState<T> stopArrivalsState) {
        assertOnlyOneWorkerIsCreated();
        createHeuristics(includeHeuristics);
        return new NoWaitRangeRaptorWorker<>(ctx, workerState(stopArrivalsState));
    }

    /**
     * The heuristics MUST be created before the worker, if not the heuristic
     * can not be added to the worker lifecycle and fails.
     */
    private void createHeuristics(boolean includeHeuristics) {
        if(!includeHeuristics) {
            return;
        }
        assertNotNull(bestNumberOfTransfers);
        heuristics = new HeuristicsAdapter(
                bestTimes(),
                this.bestNumberOfTransfers,
                ctx.egressLegs(),
                ctx.calculator(),
                ctx.lifeCycle()
        );
    }

    private StdRangeRaptorWorkerState<T> workerState(StopArrivalsState<T> stopArrivalsState) {
        return new StdRangeRaptorWorkerState<>(ctx.calculator(), bestTimes(), stopArrivalsState, destinationCheck());
    }

    private BestTimesOnlyStopArrivalsState<T> bestTimeStopArrivalsState() {
        return new BestTimesOnlyStopArrivalsState<>(bestTimes(), simpleBestNumberOfTransfers());
    }

    /**
     * Return instance if created by heuristics or null if not needed.
     */
    private SimpleBestNumberOfTransfers simpleBestNumberOfTransfers() {
        SimpleBestNumberOfTransfers value = new SimpleBestNumberOfTransfers(
                ctx.nStops(),
                ctx.roundProvider()
        );
        setBestNumberOfTransfers(value);
        return value;
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

    private Stops<T> stops() {
        if (stops == null) {
            stops = new Stops<>(
                    ctx.nRounds(),
                    ctx.nStops(),
                    ctx.roundProvider()
            );
            setBestNumberOfTransfers(stops);
        }
        return stops;
    }

    private void setBestNumberOfTransfers(BestNumberOfTransfers bestNumberOfTransfers) {
        assertSetValueIsNull("bestNumberOfTransfers", this.bestNumberOfTransfers, bestNumberOfTransfers);
        this.bestNumberOfTransfers = bestNumberOfTransfers;
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

    /**
     * This assert should only be called when creating a worker is the next step
     */
    private void assertOnlyOneWorkerIsCreated() {
        if (workerCreated) {
            throw new IllegalStateException(
                    "Create a new config for each worker. Do not reuse the config instance, " +
                            "this may lead to unpredictable behavior."
            );
        }
        workerCreated = true;
    }

    private void assertSetValueIsNull(String name, Object setValue, Object newValue) {
        if (setValue != null) {
            throw new IllegalStateException(
                    "There is more than one instance of " + name + ": " +
                            newValue.getClass().getSimpleName() + ", " +
                            setValue.getClass().getSimpleName()
            );
        }
    }

    private void assertNotNull(Object value) {
        if(value == null) {
            throw new NullPointerException();
        }
    }
}
