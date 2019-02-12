package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.request.MultiCriteriaCostFactors;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;

import java.util.Collection;

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
    private final boolean searchForward;
    private final DebugRequest<T> debugRequest;
    private final DebugHandlerFactory<T> debugFactory;


    public SearchContext(
            RangeRaptorRequest<T> request,
            TuningParameters tuningParameters,
            TransitDataProvider<T> transit,
            WorkerPerformanceTimers timers,
            boolean forward
    ) {
        this.request = request;
        this.tuningParameters = tuningParameters;
        this.transit = transit;
        this.searchForward = forward;
        // Note that it is the "new" request that is passed in.
        this.calculator = createCalculator(this.request, tuningParameters, forward);
        this.timers = timers;
        this.debugRequest = debugRequest(request, forward);
        this.debugFactory = new DebugHandlerFactory<>(this.debugRequest);
    }

    public Collection<TransferLeg> accessLegs() {
        return searchForward ? request.accessLegs() : request.egressLegs();
    }

    public Collection<TransferLeg> egressLegs() {
        return searchForward ? request.egressLegs() : request.accessLegs();
    }

    public MultiCriteriaCostFactors multiCriteriaCostFactors() {
        return request.multiCriteriaCostFactors();
    }

    public DebugRequest<T> debugRequest() {
        return debugRequest;
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
        return debugFactory;
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

    private DebugRequest<T> debugRequest(RangeRaptorRequest<T> request, boolean forward) {
        return forward ? request.debug() : new ReverseDebugRequest<>(request.debug());
    }
}
