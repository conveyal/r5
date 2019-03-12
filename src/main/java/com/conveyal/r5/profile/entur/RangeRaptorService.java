package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.configure.StdRangeRaptorConfig;
import com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;

import java.util.Collection;

/**
 * A service for performing Range Raptor routing request. 
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private enum ServiceType {
        MultiCriteria("MC", FORWARD),
        StdRR("RR", FORWARD),
        StdRRRev("RR-R", REVERSE),
        BT("BT", FORWARD),
        BTRev("BT-R", REVERSE),
        Heur("Heur", FORWARD),
        HeurRev("Heur-R", REVERSE),
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
                return createWorker(ServiceType.MultiCriteria, transitData, request);
            case MULTI_CRITERIA_RANGE_RAPTOR_WITH_HEURISTICS:
                return createMcRRHeuristicWorker(transitData, request);
            case MULTI_CRITERIA_RANGE_RAPTOR_PRUNE_STOPS:
                return createMcRRStopFilterWorker(transitData, request);
            case RANGE_RAPTOR:
                return createWorker(ServiceType.StdRR, transitData, request);
            case RAPTOR_REVERSE:
                return createWorker(ServiceType.StdRRRev, transitData, request);
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
        SearchContext<T> heurCtx = context(transitData, asOnePass(request), ServiceType.HeurRev);
        StdRangeRaptorConfig<T> stdFactory = new StdRangeRaptorConfig<>(heurCtx);

        Heuristics heuristics = stdFactory.heuristics();
        Worker<T> heurWorker = stdFactory.createNoWaitHeuristicsSearch();

        SearchContext<T> ctx = context(transitData, request, ServiceType.MultiCriteria);

        return () -> {
            heurWorker.route();
            return new McRangeRaptorWorker<>(ctx, heuristics).route();
        };
    }

    private Worker<T> createMcRRStopFilterWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        HeuristicSearch<T> fwdHeur = createHeuristicSearch(transitData, request, ServiceType.Heur);
        HeuristicSearch<T> revHeur = createHeuristicSearch(transitData, request, ServiceType.HeurRev);
        SearchContext<T> ctx = context(transitData, request, ServiceType.MultiCriteria);

        return () -> {
            fwdHeur.route();
            revHeur.route();

            DebugHeuristics.debug(fwdHeur.heuristics(), revHeur.heuristics(), ctx);

            int nTransfersLimit = fwdHeur.heuristics().bestOverallJourneyNumOfTransfers() +
                    ctx.numberOfAdditionalTransfers();

            ctx.setStopFilter(fwdHeur.heuristics().stopFilter(revHeur.heuristics(), nTransfersLimit));

            return new McRangeRaptorWorker<>(ctx, revHeur.heuristics()).route();
        };
    }

    private SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request, ServiceType type) {
        return new SearchContext<>(request, tuningParameters, transit, type.timer, type.forward);
    }

    private Worker<T> createWorker(ServiceType type, SearchContext<T> context) {
        if(type == ServiceType.MultiCriteria) {
            return new McRangeRaptorWorker<>(context, null);
        }

        StdRangeRaptorConfig<T> stdFactory = new StdRangeRaptorConfig<>(context);

        switch (type) {
            case StdRR:
            case StdRRRev:
                return stdFactory.createStandardSearch();
            case BT:
            case BTRev:
                return stdFactory.createBestTimeSearch();
            case Heur:
            case HeurRev:
                return stdFactory.createNoWaitHeuristicsSearch();
            default:
                throw new IllegalStateException();
        }
    }

    private HeuristicSearch<T> createHeuristicSearch(
            TransitDataProvider<T> transitData,
            RangeRaptorRequest<T> request,
            ServiceType heuristicServiceType
    ) {
        SearchContext<T> context = context(transitData, asOnePass(request), heuristicServiceType);
        StdRangeRaptorConfig<T> stdFactory = new StdRangeRaptorConfig<>(context);
        return stdFactory.createHeuristicSearch(context);
    }

}
