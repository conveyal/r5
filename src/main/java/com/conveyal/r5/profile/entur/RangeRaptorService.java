package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorProfile;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.configure.StdRangeRaptorConfig;
import com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;
import com.conveyal.r5.profile.entur.service.DebugHeuristics;
import com.conveyal.r5.profile.entur.service.RequestAlias;
import com.conveyal.r5.profile.entur.service.WorkerPerformanceTimersCache;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.conveyal.r5.profile.entur.api.request.Optimization.PARALLEL;
import static com.conveyal.r5.profile.entur.api.request.Optimization.PARETO_CHECK_AGAINST_DESTINATION;
import static com.conveyal.r5.profile.entur.api.request.Optimization.TRANSFERS_STOP_FILTER;
import static com.conveyal.r5.profile.entur.api.request.RangeRaptorProfile.NO_WAIT_BEST_TIME;
import static com.conveyal.r5.profile.entur.rangeraptor.standard.configure.StdRangeRaptorConfig.createHeuristicSearch;

/**
 * A service for performing Range Raptor routing request. 
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private static final boolean FORWARD = true;
    private static final boolean REVERSE = false;


    private final ExecutorService threadPool;
    private final TuningParameters tuningParameters;
    private final WorkerPerformanceTimersCache timers;

    public RangeRaptorService(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
        this.threadPool = createNewThreadPool(tuningParameters.searchThreadPoolSize());
        this.timers = new WorkerPerformanceTimersCache(isMultiThreaded());
    }

    public Collection<Path<T>> route(RangeRaptorRequest<T> request, TransitDataProvider<T> transitData) {
        if (request.profile() == RangeRaptorProfile.MULTI_CRITERIA) {
            return createMcWorker(transitData, request);
        } else {
            return routeUsingStdWorker(transitData, request);
        }
    }

    public void compareHeuristics(RangeRaptorRequest<T> r1, RangeRaptorRequest<T> r2, TransitDataProvider<T> transitData) {
        HeuristicSearch<T> h1 = createHeuristicSearch(context(transitData, heuristicReq(r1, r1.searchForward())));
        HeuristicSearch<T> h2 = createHeuristicSearch(context(transitData, heuristicReq(r2, r2.searchForward())));

        runInParallel(r1, h1, h2);

        DebugHeuristics.debug(alias(r1), h1.heuristics(), alias(r2), h2.heuristics(), context(transitData, r1));
    }

    public void shutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    /* private methods */

    private Collection<Path<T>> routeUsingStdWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        return StdRangeRaptorConfig.createSearch(context(transitData, request)).route();
    }

    private Collection<Path<T>> createMcWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        final SearchContext<T> context = context(transitData, request);
        HeuristicSearch<T> fwdHeur;
        HeuristicSearch<T> revHeur;
        Heuristics destinationArrivalHeuristicsCheck = null;

        if (request.optimizationEnabled(TRANSFERS_STOP_FILTER)) {
            fwdHeur = createHeuristicSearch(context(transitData, heuristicReq(request, NO_WAIT_BEST_TIME, FORWARD)));
            revHeur = createHeuristicSearch(context(transitData, heuristicReq(request, NO_WAIT_BEST_TIME, REVERSE)));

            runInParallel(request, revHeur, fwdHeur);

            context.setStopFilter(fwdHeur.stopFilter(revHeur, context.numberOfAdditionalTransfers()));

            if (request.optimizationEnabled(PARETO_CHECK_AGAINST_DESTINATION)) {
                destinationArrivalHeuristicsCheck = revHeur.heuristics();
            }
            DebugHeuristics.debug("Forward", fwdHeur.heuristics(), "Reverse", revHeur.heuristics(), context);
        } else if (request.optimizationEnabled(PARETO_CHECK_AGAINST_DESTINATION)) {
            revHeur = createHeuristicSearch(context(transitData, heuristicReq(request, NO_WAIT_BEST_TIME, REVERSE)));
            revHeur.route();
            destinationArrivalHeuristicsCheck = revHeur.heuristics();
        }
        return new McRangeRaptorWorker<>(context, destinationArrivalHeuristicsCheck).route();
    }

    private void runInParallel(RangeRaptorRequest<T> request, Worker<?> w1, Worker<?> w2) {
        if (runInParallel(request)) {
            Future<?> f = threadPool.submit(w2::route);
            w1.route();
            try {
                f.get();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage(), e);
            }
        } else {
            w1.route();
            w2.route();
        }
    }

    private SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request) {
        return new SearchContext<>(request, tuningParameters, transit, timers.get(request));
    }

    private RangeRaptorRequest<T> heuristicReq(RangeRaptorRequest<T> request, boolean forward) {
        return request.mutate().searchWindowInSeconds(0).searchDirection(forward).build();
    }

    private RangeRaptorRequest<T> heuristicReq(RangeRaptorRequest<T> request, RangeRaptorProfile profile, boolean forward) {
        return request.mutate().searchWindowInSeconds(0).profile(profile).searchDirection(forward).build();
    }

    private ExecutorService createNewThreadPool(int size) {
        return size > 0 ? Executors.newFixedThreadPool(size) : null;
    }

    private String alias(RangeRaptorRequest<?> request) {
        return RequestAlias.alias(request, runInParallel(request));
    }

    private boolean runInParallel(RangeRaptorRequest<?> request) {
        return isMultiThreaded() && request.optimizationEnabled(PARALLEL);
    }

    private boolean isMultiThreaded() {
        return threadPool != null;
    }
}
