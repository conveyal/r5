package com.conveyal.r5.otp2.rangeraptor.configure;

import com.conveyal.r5.otp2.api.request.RangeRaptorProfile;
import com.conveyal.r5.otp2.api.request.RangeRaptorRequest;
import com.conveyal.r5.otp2.api.request.RequestBuilder;
import com.conveyal.r5.otp2.api.request.TuningParameters;
import com.conveyal.r5.otp2.api.transit.TransitDataProvider;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.Heuristics;
import com.conveyal.r5.otp2.api.view.Worker;
import com.conveyal.r5.otp2.rangeraptor.TransitRoutingStrategy;
import com.conveyal.r5.otp2.rangeraptor.RangeRaptorWorker;
import com.conveyal.r5.otp2.rangeraptor.WorkerState;
import com.conveyal.r5.otp2.rangeraptor.multicriteria.configure.McRangeRaptorConfig;
import com.conveyal.r5.otp2.rangeraptor.standard.configure.StdRangeRaptorConfig;
import com.conveyal.r5.otp2.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.otp2.rangeraptor.transit.SearchContext;
import com.conveyal.r5.otp2.service.WorkerPerformanceTimersCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * This class is responsible for creating a new search and holding
 * application scoped Range Raptor state.
 * <p/>
 * This class should have APPLICATION scope. It manage a threadPool,
 * and hold a reference to the application tuning parameters.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RangeRaptorConfig<T extends TripScheduleInfo> {
    private final ExecutorService threadPool;
    private final TuningParameters tuningParameters;
    private final WorkerPerformanceTimersCache timers;


    public RangeRaptorConfig(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
        this.threadPool = createNewThreadPool(tuningParameters.searchThreadPoolSize());
        this.timers = new WorkerPerformanceTimersCache(isMultiThreaded());
    }

    public SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request) {
        return new SearchContext<>(request, tuningParameters, transit, timers.get(request));
    }

    public Worker<T> createStdWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request);
        return new StdRangeRaptorConfig<>(context).createSearch((s, w) -> createWorker(context, s, w));
    }

    public Worker<T> createMcWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request, Heuristics heuristics) {
        final SearchContext<T> context = context(transitData, request);
        return new McRangeRaptorConfig<>(context).createWorker(heuristics, (s, w) -> createWorker(context, s, w));
    }

    public HeuristicSearch<T> createHeuristicSearch(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request);
        return new StdRangeRaptorConfig<>(context).createHeuristicSearch((s, w) -> createWorker(context, s, w));
    }

    public HeuristicSearch<T> createHeuristicSearch(
            TransitDataProvider<T> transitData,
            RangeRaptorProfile profile,
            RangeRaptorRequest<T> request,
            boolean forward
    ) {
        RangeRaptorRequest<T> req = heuristicReq(request, profile, forward);
        return createHeuristicSearch(transitData, req);
    }

    public boolean isMultiThreaded() {
        return threadPool != null;
    }

    public ExecutorService threadPool() {
        return threadPool;
    }

    public void shutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    /* private factory methods */

    private Worker<T> createWorker(
            SearchContext<T> ctx,
            WorkerState<T> workerState,
            TransitRoutingStrategy<T> transitRoutingStrategy
    ) {
        return new RangeRaptorWorker<>(
                workerState,
                transitRoutingStrategy,
                ctx.transit(),
                ctx.accessLegs(),
                ctx.roundProvider(),
                ctx.calculator(),
                ctx.createLifeCyclePublisher(),
                ctx.timers(),
                ctx.searchParams().waitAtBeginningEnabled()
        );
    }

    private RangeRaptorRequest<T> heuristicReq(RangeRaptorRequest<T> request, RangeRaptorProfile profile, boolean forward) {
        RequestBuilder<T> copy = request.mutate();
        copy.profile(profile).searchDirection(forward);
        copy.searchParams().searchOneIterationOnly();
        return copy.build();
    }

    private ExecutorService createNewThreadPool(int size) {
        return size > 0 ? Executors.newFixedThreadPool(size) : null;
    }

}
