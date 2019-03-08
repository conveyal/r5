package com.conveyal.r5.profile.entur.rangeraptor.standard.create;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.CalculateHeuristicWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.NoWaitRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.ArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimesOnlyStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.SimpleArrivedAtDestinationCheck;
import com.conveyal.r5.profile.entur.rangeraptor.standard.debug.DebugStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.DestinationArrivals;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.StdStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.Stops;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopsCursor;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;


/**
 * The responsibility of this class is to wire different standard range raptor
 * worker configurations together based on the context passed into the class.
 * There is a factory (create) method for each legal configuration.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StdRangeRaptorFactory<T extends TripScheduleInfo> {
    private final SearchContext<T> ctx;
    private BestTimes bestTimes = null;
    private ArrivedAtDestinationCheck destinationCheck = null;
    private DestinationArrivals<T> destinationArrivals = null;
    private Stops<T> stops = null;
    private CalculateHeuristicWorkerState<T> heuristicWorkerState = null;


    public StdRangeRaptorFactory(SearchContext<T> context) {
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
        destinationArrivals = null;
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
        StdStopArrivalsState<T> state = new StdStopArrivalsState<>(stops(), destinationArrivals());
        return wrapStopArrivalsStateWithDebugger(state);
    }

    private StopArrivalsState<T> wrapStopArrivalsStateWithDebugger(StopArrivalsState<T> state) {
        if (ctx.debugFactory().isDebugStopArrival()) {
            return new DebugStopArrivalsState<T>(ctx.roundProvider(), ctx.debugFactory(), stopsCursor(), state);
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
            stops = new Stops<>(ctx.nRounds(), ctx.nStops(), ctx.roundProvider());
        }
        return stops;
    }

    private StopsCursor<T> stopsCursor() {
        // Always create new cursors
        return new StopsCursor<T>(stops(), ctx.calculator());
    }

    private DestinationArrivals<T> destinationArrivals() {
        if (destinationArrivals == null) {
            this.destinationArrivals = new DestinationArrivals<T>(
                    ctx.nRounds(),
                    ctx.calculator(),
                    stopsCursor(),
                    ctx.debugFactory(),
                    ctx.lifeCycle()
            );
            // The DestinationArrivals also play the role of the destination arrival check
            setDestinationCheck(destinationArrivals);

            // To avoid stack overflow, create stops, then destinationArrivals, and then
            // attach egress stop arrivals to the destination (add them self when reached)
            stops().setupEgressStopStates(ctx.egressLegs(), destinationArrivals::add);

        }
        return destinationArrivals;
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
