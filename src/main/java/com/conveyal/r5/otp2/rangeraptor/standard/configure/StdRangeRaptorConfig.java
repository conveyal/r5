package com.conveyal.r5.otp2.rangeraptor.standard.configure;

import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.Heuristics;
import com.conveyal.r5.otp2.api.view.Worker;
import com.conveyal.r5.otp2.rangeraptor.TransitRoutingStrategy;
import com.conveyal.r5.otp2.rangeraptor.WorkerState;
import com.conveyal.r5.otp2.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.otp2.rangeraptor.path.configure.PathConfig;
import com.conveyal.r5.otp2.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.otp2.rangeraptor.standard.BestNumberOfTransfers;
import com.conveyal.r5.otp2.rangeraptor.standard.NoWaitTransitWorker;
import com.conveyal.r5.otp2.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.otp2.rangeraptor.standard.StdTransitWorker;
import com.conveyal.r5.otp2.rangeraptor.standard.StdWorkerState;
import com.conveyal.r5.otp2.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.otp2.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.otp2.rangeraptor.standard.besttimes.BestTimesOnlyStopArrivalsState;
import com.conveyal.r5.otp2.rangeraptor.standard.besttimes.SimpleArrivedAtDestinationCheck;
import com.conveyal.r5.otp2.rangeraptor.standard.besttimes.SimpleBestNumberOfTransfers;
import com.conveyal.r5.otp2.rangeraptor.standard.debug.DebugStopArrivalsState;
import com.conveyal.r5.otp2.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.otp2.rangeraptor.standard.heuristics.HeuristicsAdapter;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.StdStopArrivalsState;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.Stops;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.path.EgressArrivalToPathAdapter;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view.StopsCursor;
import com.conveyal.r5.otp2.rangeraptor.transit.SearchContext;

import java.util.function.BiFunction;


/**
 * The responsibility of this class is to wire different standard range raptor
 * worker configurations together based on the context passed into the class.
 * There is a factory (create) method for each legal configuration.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StdRangeRaptorConfig<T extends TripScheduleInfo> {

    private final SearchContext<T> ctx;
    private final PathConfig<T> pathConfig;

    private boolean workerCreated = false;
    private BestTimes bestTimes = null;
    private Stops<T> stops = null;
    private ArrivedAtDestinationCheck destinationCheck = null;
    private BestNumberOfTransfers bestNumberOfTransfers = null;


    public StdRangeRaptorConfig(SearchContext<T> context) {
        this.ctx = context;
        this.pathConfig = new PathConfig<>(context);
    }

    /**
     * Create a heuristic search using the provided callback to create the worker.
     * The callback is necessary because the heuristics MUST be created before the
     * worker, if not the heuristic can not be added to the worker lifecycle and fails.
     */
    public HeuristicSearch<T> createHeuristicSearch(
            BiFunction<WorkerState<T>, TransitRoutingStrategy<T>, Worker<T>> createWorker
    ) {
        StdRangeRaptorWorkerState<T> state = createState();
        Heuristics heuristics = createHeuristicsAdapter();
        return new HeuristicSearch<>(createWorker.apply(state, createWorkerStrategy(state)), heuristics);
    }

    public Worker<T> createSearch(
            BiFunction<WorkerState<T>, TransitRoutingStrategy<T>, Worker<T>> createWorker
    ) {
        StdRangeRaptorWorkerState<T> state = createState();
        return createWorker.apply(state, createWorkerStrategy(state));
    }


    /* private factory methods */

    private StdRangeRaptorWorkerState<T> createState() {
        new VerifyRequestIsValid(ctx).verify();
        switch (ctx.profile()) {
            case STANDARD:
            case NO_WAIT_STD:
                return workerState(stdStopArrivalsState());
            case BEST_TIME:
            case NO_WAIT_BEST_TIME:
                return workerState(bestTimeStopArrivalsState());
        }
        throw new IllegalArgumentException(ctx.profile().toString());
    }

    private TransitRoutingStrategy<T> createWorkerStrategy(StdWorkerState<T> state) {

        switch (ctx.profile()) {
            case STANDARD:
            case BEST_TIME:
                return new StdTransitWorker<>(state, ctx.calculator());
            case NO_WAIT_STD:
            case NO_WAIT_BEST_TIME:
                return new NoWaitTransitWorker<>(state, ctx.calculator());
        }
        throw new IllegalArgumentException(ctx.profile().toString());
    }

    private Heuristics createHeuristicsAdapter() {
        assertNotNull(bestNumberOfTransfers);
        return new HeuristicsAdapter(
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
        DestinationArrivalPaths<T> destinationArrivalPaths = pathConfig.createDestArrivalPaths(false);

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
