package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.CalculateHeuristicWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.NoWaitRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.standard.BestTimesOnlyStopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StdWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

import java.util.Collection;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StdStopArrivalsState.createStdWorkerState;

/**
 * A service for performing Range Raptor routing request. 
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private enum ServiceType {
        multiCriteria("MC", FORWARD),
        stdRR("RR", FORWARD),
        stdRRRev("RR-R", REVERSE),
        BT("BT", FORWARD),
        BTRev("BT-R", REVERSE),
        Heur("Heur-R", REVERSE),
        ;
        WorkerPerformanceTimers timer;
        boolean forward;

        ServiceType(String timerName, boolean forward) {
            this.timer = new WorkerPerformanceTimers(timerName);
            this.forward = forward;
        }
    }

    private static final boolean FORWARD = true;
    private static final boolean REVERSE = false;


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
                return createWorker(ServiceType.multiCriteria, transitData, request);
            case MULTI_CRITERIA_RANGE_RAPTOR_WITH_HEURISTICS:
                return createMcRRHeuristicWorker(transitData, request);
            case RANGE_RAPTOR:
                return createWorker(ServiceType.stdRR, transitData, request);
            case RAPTOR_REVERSE:
                return createWorker(ServiceType.stdRRRev, transitData, request);
            case RANGE_RAPTOR_BEST_TIME:
                return createWorker(ServiceType.BT, transitData, request);
            default:
                throw new IllegalStateException("Unknown profile: " + this);
        }
    }

    private Worker<T> createWorker(ServiceType type, TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request, type);
        return createWorker(type, context);
    }

    private RangeRaptorRequest<T> asOnePass( RangeRaptorRequest<T> request) {
        return request.mutate().searchWindowInSeconds(tuningParameters.iterationDepartureStepInSeconds()).build();
    }

    private Worker<T> createMcRRHeuristicWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> heurCtx = context(transitData, asOnePass(request), ServiceType.Heur);

        BestTimes bt2 = new BestTimes(heurCtx);
        CalculateHeuristicWorkerState<T> heuristic = new CalculateHeuristicWorkerState<>(heurCtx, bt2);
        StdWorkerState<T> state = new StdRangeRaptorWorkerState<>(heurCtx, bt2, heuristic);
        Worker<T> heurWorker = new StdRangeRaptorWorker<>(context(transitData, request, ServiceType.Heur), state);

        SearchContext<T> ctx = context(transitData, request, ServiceType.multiCriteria);

        return () -> {
            heurWorker.route();
            return new McRangeRaptorWorker<>(ctx, heuristic.heuristic()).route();
        };
    }


    private SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request, ServiceType type) {
        return new SearchContext<>(request, tuningParameters, transit, type.timer, type.forward);
    }

    private Worker<T> createWorker(ServiceType type, SearchContext<T> context) {
        if(type == ServiceType.multiCriteria) {
            return new McRangeRaptorWorker<>(context, null);
        }

        StdWorkerState<T> state = createState(context, type);

        if(type == ServiceType.Heur) {
            return new NoWaitRangeRaptorWorker<>(context, state);
        }

        return new StdRangeRaptorWorker<>(context, state);
    }

    private StdWorkerState<T> createState(SearchContext<T> context, ServiceType type) {
        switch (type) {
            case stdRR:
            case stdRRRev:
                BestTimes bt0 = new BestTimes(context);
                StopArrivalsState<T> s0 = createStdWorkerState(context);
                return new StdRangeRaptorWorkerState<>(context, bt0, s0);
            case BT:
            case BTRev:
                BestTimes bt1 = new BestTimes(context);
                StopArrivalsState<T> s1 = new BestTimesOnlyStopArrivalsState<>(bt1);
                return new StdRangeRaptorWorkerState<>(context, bt1, s1);
            case Heur:
                BestTimes bt2 = new BestTimes(context);
                StopArrivalsState<T> s2 = new CalculateHeuristicWorkerState<>(context, bt2);
                return new StdRangeRaptorWorkerState<>(context, bt2, s2);
            default:
                throw new IllegalStateException();
        }
    }
}
