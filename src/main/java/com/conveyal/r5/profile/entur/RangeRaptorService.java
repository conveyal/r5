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
import com.conveyal.r5.profile.entur.rangeraptor.standard.configure.StdRangeRaptorConfig;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

import java.util.Collection;

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
        StdRangeRaptorConfig<T> stdFactory = new StdRangeRaptorConfig<>(heurCtx);

        Worker<T> heurWorker = stdFactory.createStdHeuristicsSearch();
        CalculateHeuristicWorkerState<T> heurProvider = stdFactory.getHeuristicWorkerState();

        SearchContext<T> ctx = context(transitData, request, ServiceType.multiCriteria);

        return () -> {
            heurWorker.route();
            return new McRangeRaptorWorker<>(ctx, heurProvider.heuristic()).route();
        };
    }


    private SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request, ServiceType type) {
        return new SearchContext<>(request, tuningParameters, transit, type.timer, type.forward);
    }

    private Worker<T> createWorker(ServiceType type, SearchContext<T> context) {
        if(type == ServiceType.multiCriteria) {
            return new McRangeRaptorWorker<>(context, null);
        }

        StdRangeRaptorConfig<T> stdFactory = new StdRangeRaptorConfig<>(context);

        switch (type) {
            case stdRR:
            case stdRRRev:
                return stdFactory.createStandardSearch();
            case BT:
            case BTRev:
                return stdFactory.createBestTimeSearch();
            case Heur:
                return stdFactory.createNoWaitHeuristicsSearch();
            default:
                throw new IllegalStateException();
        }
    }
}
