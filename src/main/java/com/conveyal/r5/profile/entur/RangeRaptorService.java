package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.BestTimesWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

import java.util.Collection;

/**
 * A service for performing Range Raptor routing request. 
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private static final boolean FORWARD = true;
    private static final boolean BACKWARD = false;
    private static final WorkerPerformanceTimers MC_TIMERS = new WorkerPerformanceTimers("MC");
    private static final WorkerPerformanceTimers RR_TIMERS = new WorkerPerformanceTimers("RR");
    private static final WorkerPerformanceTimers BT_TIMERS = new WorkerPerformanceTimers("BT");

    private final TuningParameters tuningParameters;

    public RangeRaptorService(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
    }

    public Collection<Path<T>> route(RangeRaptorRequest<T> request, TransitDataProvider<T> transitData) {
        Worker<T> worker = createWorker(request, transitData);
        return worker.route();
    }


    /* private methods */

    private Worker<T> createWorker(RangeRaptorRequest<T> request, TransitDataProvider<T> transitData) {
        switch (request.profile()) {
            case MULTI_CRITERIA_RANGE_RAPTOR:
                return createMcRRWorker(transitData, request);
            case RAPTOR_REVERSE:
                return createReversWorker(transitData, request);
            case RANGE_RAPTOR:
                return createRRWorker(transitData, request);
            default:
                throw new IllegalStateException("Unknown profile: " + this);
        }
    }

    private Worker<T> createMcRRWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        return new McRangeRaptorWorker<>(context(transitData, request, MC_TIMERS, FORWARD), null);

        /*
        SearchContext<T> contextHeuristic = context(transitData, request, RR_TIMERS, BACKWARD);
        SearchContext<T> mcContext = context(transitData, request, MC_TIMERS, FORWARD);
        StdWorkerState<T> stateHeuristic = stdState(contextHeuristic);

        return () -> {
            new StdRangeRaptorWorker<>(contextHeuristic, stateHeuristic).route();
            return new McRangeRaptorWorker<>(mcContext, null).route();
        };
        */

    }

    private Worker<T> createRRWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request, RR_TIMERS, FORWARD);
        return new StdRangeRaptorWorker<>(context, stdState(context));
    }

    private Worker<T> createReversWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request, BT_TIMERS, BACKWARD);
        return new StdRangeRaptorWorker<>(context, stdState(context));
    }

    private SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request, WorkerPerformanceTimers timers, boolean forward) {
        return new SearchContext<>(request, tuningParameters, transit, timers, forward);
    }

    private StdWorkerState<T> stdState(SearchContext<T> context) {
        return new StdRangeRaptorWorkerState<>(context);
    }

    private StdWorkerState<T> bestTimeState(SearchContext<T> context) {
        return new BestTimesWorkerState<>(context);
    }
}
