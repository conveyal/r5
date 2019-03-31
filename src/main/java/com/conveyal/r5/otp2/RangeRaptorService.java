package com.conveyal.r5.otp2;

import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.api.request.RangeRaptorProfile;
import com.conveyal.r5.otp2.api.request.RangeRaptorRequest;
import com.conveyal.r5.otp2.api.request.RequestBuilder;
import com.conveyal.r5.otp2.api.request.TuningParameters;
import com.conveyal.r5.otp2.api.transit.TransitDataProvider;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.Heuristics;
import com.conveyal.r5.otp2.api.view.Worker;
import com.conveyal.r5.otp2.rangeraptor.configure.RangeRaptorConfig;
import com.conveyal.r5.otp2.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.otp2.service.DebugHeuristics;
import com.conveyal.r5.otp2.service.RequestAlias;

import java.util.BitSet;
import java.util.Collection;
import java.util.concurrent.Future;

import static com.conveyal.r5.otp2.api.request.Optimization.PARALLEL;
import static com.conveyal.r5.otp2.api.request.Optimization.PARETO_CHECK_AGAINST_DESTINATION;
import static com.conveyal.r5.otp2.api.request.Optimization.TRANSFERS_STOP_FILTER;
import static com.conveyal.r5.otp2.api.request.RangeRaptorProfile.NO_WAIT_BEST_TIME;

/**
 * A service for performing Range Raptor routing request.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private static final boolean FORWARD = true;
    private static final boolean REVERSE = false;

    private final RangeRaptorConfig<T> config;

    public RangeRaptorService(TuningParameters tuningParameters) {
        this.config = new RangeRaptorConfig<>(tuningParameters);
    }

    public Collection<Path<T>> route(RangeRaptorRequest<T> request, TransitDataProvider<T> transitData) {
        if (request.profile() == RangeRaptorProfile.MULTI_CRITERIA) {
            return createMcWorker(transitData, request);
        } else {
            return routeUsingStdWorker(transitData, request);
        }
    }

    public void compareHeuristics(RangeRaptorRequest<T> r1, RangeRaptorRequest<T> r2, TransitDataProvider<T> transitData) {
        HeuristicSearch<T> h1 = config.createHeuristicSearch(transitData, r1);
        HeuristicSearch<T> h2 = config.createHeuristicSearch(transitData, r2);
        runInParallel(r1, h1, h2);
        DebugHeuristics.debug(alias(r1), h1.heuristics(), alias(r2), h2.heuristics(), r1);
    }

    public void shutdown() {
        config.shutdown();
    }

    /* private methods */

    private Collection<Path<T>> routeUsingStdWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        return config.createStdWorker(transitData, request).route();
    }

    private Collection<Path<T>> createMcWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        HeuristicSearch<T> fwdHeur;
        HeuristicSearch<T> revHeur;
        Heuristics destinationArrivalHeuristicsCheck = null;
        RangeRaptorRequest<T> mcRequest = request;
        final int nAdditionalTransfers = request.searchParams().numberOfAdditionalTransfers();

        if (request.optimizationEnabled(TRANSFERS_STOP_FILTER)) {
            fwdHeur = config.createHeuristicSearch(transitData, NO_WAIT_BEST_TIME, request, FORWARD);
            revHeur = config.createHeuristicSearch(transitData, NO_WAIT_BEST_TIME, request, REVERSE);

            runInParallel(request, revHeur, fwdHeur);

            mcRequest = addStopFilterToRequest(mcRequest, fwdHeur.stopFilter(revHeur, nAdditionalTransfers));

            if (request.optimizationEnabled(PARETO_CHECK_AGAINST_DESTINATION)) {
                destinationArrivalHeuristicsCheck = revHeur.heuristics();
            }
            debugHeuristicResut(request, fwdHeur, revHeur);

        } else if (request.optimizationEnabled(PARETO_CHECK_AGAINST_DESTINATION)) {
            revHeur = config.createHeuristicSearch(transitData, NO_WAIT_BEST_TIME, request, REVERSE);
            revHeur.route();
            destinationArrivalHeuristicsCheck = revHeur.heuristics();
        }
        return config.createMcWorker(transitData, mcRequest, destinationArrivalHeuristicsCheck).route();
    }

    private RangeRaptorRequest<T> addStopFilterToRequest(RangeRaptorRequest<T> request, BitSet stopFilter) {
        RequestBuilder<T> reqBuilder = request.mutate();
        reqBuilder.searchParams().stopFilter(stopFilter);
        return reqBuilder.build();
    }

    private void debugHeuristicResut(RangeRaptorRequest<?> req, HeuristicSearch<T> fwdHeur, HeuristicSearch<T> revHeur) {
        DebugHeuristics.debug("Forward", fwdHeur.heuristics(), "Reverse", revHeur.heuristics(), req);
    }

    private void runInParallel(RangeRaptorRequest<T> request, Worker<?> w1, Worker<?> w2) {
        if (!runInParallel(request)) {
            w1.route();
            w2.route();
            return;
        }
        try {
            Future<?> f = config.threadPool().submit(w2::route);
            w1.route();
            f.get();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String alias(RangeRaptorRequest<?> request) {
        return RequestAlias.alias(request, isMultiThreaded());
    }

    private boolean runInParallel(RangeRaptorRequest<?> request) {
        return isMultiThreaded() && request.optimizationEnabled(PARALLEL);
    }

    private boolean isMultiThreaded() {
        return config.isMultiThreaded();
    }
}
