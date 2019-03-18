package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.configure;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.view.Heuristics;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

public class McRangeRaptorConfig<T extends TripScheduleInfo> {
    private SearchContext<T> context;

    public McRangeRaptorConfig(SearchContext<T> context) {
        this.context = context;
    }

    public McRangeRaptorWorker<T> createWorker(Heuristics heuristics) {
        return new McRangeRaptorWorker<>(context, createState(heuristics));
    }


    /* private factory methods */

    private McRangeRaptorWorkerState<T> createState(Heuristics heuristics) {
        return new McRangeRaptorWorkerState<>(
                context.nStops(),
                context.tuningParameters().relaxCostAtDestinationArrival(),
                context.egressLegs(),
                heuristics,
                context.roundProvider(),
                context.costCalculator(),
                context.calculator(),
                context.debugLogger(),
                context.debugFactory(),
                context.lifeCycle()
        );
    }
}
