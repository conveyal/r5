package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.configure;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.view.Heuristics;
import com.conveyal.r5.profile.entur.api.view.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.PerformTransitStrategy;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McTransitWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.Stops;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic.HeuristicsProvider;
import com.conveyal.r5.profile.entur.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.profile.entur.rangeraptor.path.configure.PathConfig;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

import javax.annotation.Nullable;
import java.util.function.BiFunction;


/**
 * Configure and create multicriteria worker, state and child classes.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class McRangeRaptorConfig<T extends TripScheduleInfo> {
    private final SearchContext<T> context;
    private final PathConfig<T> pathConfig;

    private DestinationArrivalPaths<T> paths;

    public McRangeRaptorConfig(SearchContext<T> context) {
        this.context = context;
        this.pathConfig = new PathConfig<>(context);
    }

    /**
     * Create new multi-criteria worker with optional heuristics.
     */
    public Worker<T> createWorker(
            @Nullable Heuristics heuristics,
            BiFunction<WorkerState<T>, PerformTransitStrategy<T>, Worker<T>> createWorker
    ) {
        McRangeRaptorWorkerState<T> state = createState(heuristics);
        return createWorker.apply(state, createTransitWorkerStrategy(state));
    }


    /* private factory methods */

    private PerformTransitStrategy<T> createTransitWorkerStrategy(McRangeRaptorWorkerState<T> state) {
        return new McTransitWorker<>(state, context.stopFilter(), context.calculator());
    }

    private McRangeRaptorWorkerState<T> createState(Heuristics heuristics) {
        return new McRangeRaptorWorkerState<>(
                createStops(),
                createDestinationArrivalPaths(),
                createHeuristicsProvider(heuristics),
                context.costCalculator(),
                context.calculator(),
                context.lifeCycle()
        );
    }

    private Stops<T> createStops() {
        return new Stops<>(
                context.nStops(),
                context.egressLegs(),
                createDestinationArrivalPaths(),
                context.costCalculator(),
                context.debugFactory(),
                context.debugLogger()
        );
    }

    private HeuristicsProvider<T> createHeuristicsProvider(@Nullable Heuristics heuristics) {
        if (heuristics == null) {
            return new HeuristicsProvider<>();
        } else {
            return new HeuristicsProvider<>(
                    heuristics,
                    context.roundProvider(),
                    createDestinationArrivalPaths(),
                    context.costCalculator(),
                    context.debugFactory()
            );
        }
    }

    private DestinationArrivalPaths<T> createDestinationArrivalPaths() {
        if (paths == null) {
            paths = pathConfig.createDestArrivalPaths(true);
        }
        return paths;
    }
}
