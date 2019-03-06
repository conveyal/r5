package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.request.MultiCriteriaCostFactors;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.workerlifecycle.LifeCycleBuilder;
import com.conveyal.r5.profile.entur.rangeraptor.workerlifecycle.LifeCycleEventPublisher;

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
    private final RoundTracker roundTracker;
    private final WorkerPerformanceTimers timers;
    private final boolean searchForward;
    private final DebugRequest<T> debugRequest;
    private final DebugHandlerFactory<T> debugFactory;
    private LifeCycleBuilder lifeCycleBuilder = new LifeCycleBuilder();

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
        this.roundTracker = new RoundTracker(nRounds(), request.numberOfAdditionalTransfers(), lifeCycle());
        this.timers = timers;
        this.debugRequest = debugRequest(request, forward);
        this.debugFactory = new DebugHandlerFactory<>(this.debugRequest, lifeCycle());
    }

    public Collection<TransferLeg> accessLegs() {
        return searchForward ? request.accessLegs() : request.egressLegs();
    }

    public Collection<TransferLeg> egressLegs() {
        return searchForward ? request.egressLegs() : request.accessLegs();
    }

    public int[] egressStops() {
        return egressLegs().stream().mapToInt(TransferLeg::stop).toArray();
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

    public CostCalculator costCalculator() {
        MultiCriteriaCostFactors f = request.multiCriteriaCostFactors();
        return new CostCalculator(
                f.boardCost(),
                request.boardSlackInSeconds(),
                f.walkReluctanceFactor(),
                f.waitReluctanceFactor()
        );
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
        return forward ? request.debug() : request.mutate().reverseDebugRequest().debug();
    }

    public int numberOfAdditionalTransfers() {
        return request.numberOfAdditionalTransfers();
    }

    public RoundProvider roundProvider() {
        return roundTracker;
    }

    public WorkerLifeCycle lifeCycle() {
        return lifeCycleBuilder;
    }

    public LifeCycleEventPublisher createLifeCyclePublisher() {
        LifeCycleEventPublisher publisher = new LifeCycleEventPublisher(lifeCycleBuilder);
        // We want the code to fail (NPE) if someone try to attach to the worker workerlifecycle
        // after it is iniziated; Hence set the builder to null:
        lifeCycleBuilder = null;
        return publisher;
    }

}
