package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;

public class SearchContext<T extends TripScheduleInfo> {
    /**
     * The request input used to customize the worker to the clients needs.
     */
    private final RangeRaptorRequest<T> request;

    /**
     * the transit data role needed for routing
     */
    protected final TransitDataProvider<T> transit;

    private final TransitCalculator calculator;
    private final TuningParameters tuningParameters;
    private final WorkerPerformanceTimers timers;


    public SearchContext(
            RangeRaptorRequest<T> request,
            TuningParameters tuningParameters,
            TransitDataProvider<T> transit,
            WorkerPerformanceTimers timers,
            boolean forward
    ) {
        this.request = forward ? request : new ReverseRequest<>(request);
        this.tuningParameters = tuningParameters;
        this.transit = transit;
        // Note that it is the "new" request that is passed in.
        this.calculator = createCalculator(this.request, tuningParameters, forward);
        this.timers = timers;
    }

    public RangeRaptorRequest<T> request() {
        return request;
    }

    public TuningParameters tuningParameters() {
        return tuningParameters;
    }

    public TransitDataProvider<T> transit() {
        return transit;
    }

    public TransitCalculator calculator() {
        return calculator;
    }

    public WorkerPerformanceTimers timers() {
        return timers;
    }

    public DebugHandlerFactory<T> debugFactory() {
        return new DebugHandlerFactory<>(request.debug());
    }

    /** Number of stops in transit graph. */
    public int nStops() {
        return transit.numberOfStops();
    }

    /** Calculate the maximum number of rounds to perform. */
    public int nRounds() {
        return tuningParameters.maxNumberOfTransfers() + 1;
    }

    /**
     * Create a new calculator depending on the desired search direction.
     */
    private static TransitCalculator createCalculator(RangeRaptorRequest<?> r, TuningParameters t, boolean forward) {
        return forward
                ? new ForwardSearchTransitCalculator(r, t)
                : new ReverseSearchTransitCalculator(r, t);
    }
}
