package com.conveyal.r5.otp2.rangeraptor.transit;

import com.conveyal.r5.otp2.api.debug.DebugLogger;
import com.conveyal.r5.otp2.api.request.DebugRequest;
import com.conveyal.r5.otp2.api.request.McCostParams;
import com.conveyal.r5.otp2.api.request.RangeRaptorProfile;
import com.conveyal.r5.otp2.api.request.RangeRaptorRequest;
import com.conveyal.r5.otp2.api.request.SearchParams;
import com.conveyal.r5.otp2.api.request.TuningParameters;
import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TransitDataProvider;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.RoundProvider;
import com.conveyal.r5.otp2.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.otp2.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.otp2.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.otp2.rangeraptor.workerlifecycle.LifeCycleBuilder;
import com.conveyal.r5.otp2.rangeraptor.workerlifecycle.LifeCycleEventPublisher;

import java.util.Collection;

/**
 * The seach context is used to hold search scoped instances and to pass these
 * to who ever need them.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class SearchContext<T extends TripScheduleInfo> {
    private static final DebugLogger NOOP_DEBUG_LOGGER = (topic, message) -> { };
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
    private final DebugRequest<T> debugRequest;
    private final DebugHandlerFactory<T> debugFactory;
    private final StopFilter stopFilter;

    private LifeCycleBuilder lifeCycleBuilder = new LifeCycleBuilder();

    public SearchContext(
            RangeRaptorRequest<T> request,
            TuningParameters tuningParameters,
            TransitDataProvider<T> transit,
            WorkerPerformanceTimers timers
    ) {
        this.request = request;
        this.tuningParameters = tuningParameters;
        this.transit = transit;
        // Note that it is the "new" request that is passed in.
        this.calculator = createCalculator(this.request, tuningParameters);
        this.roundTracker = new RoundTracker(nRounds(), request.searchParams().numberOfAdditionalTransfers(), lifeCycle());
        this.timers = timers;
        this.debugRequest = debugRequest(request);
        this.debugFactory = new DebugHandlerFactory<>(this.debugRequest, lifeCycle());
        this.stopFilter = request.searchParams().stopFilter() != null
                ? new StopFilerBitSet(request.searchParams().stopFilter())
                : (s -> true);
    }

    public Collection<TransferLeg> accessLegs() {
        return request.searchForward() ? request.searchParams().accessLegs() : request.searchParams().egressLegs();
    }

    public Collection<TransferLeg> egressLegs() {
        return request.searchForward() ? request.searchParams().egressLegs() : request.searchParams().accessLegs();
    }

    public int[] egressStops() {
        return egressLegs().stream().mapToInt(TransferLeg::stop).toArray();
    }

    public SearchParams searchParams() {
        return request.searchParams();
    }

    public RangeRaptorProfile profile() {
        return request.profile();
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
        McCostParams f = request.multiCriteriaCostFactors();
        return new CostCalculator(
                f.boardCost(),
                request.searchParams().boardSlackInSeconds(),
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

    public DebugLogger debugLogger() {
        DebugLogger logger = request.debug().logger();
        return logger != null ? logger : NOOP_DEBUG_LOGGER;
    }

    /** Number of stops in transit graph. */
    public int nStops() {
        return transit.numberOfStops();
    }

    /** Calculate the maximum number of rounds to perform. */
    public int nRounds() {
        return tuningParameters.maxNumberOfTransfers() + 1;
    }

    public StopFilter stopFilter() {
        return stopFilter;
    }

    /**
     * Create a new calculator depending on the desired search direction.
     */
    private static TransitCalculator createCalculator(RangeRaptorRequest<?> r, TuningParameters t) {
        SearchParams s = r.searchParams();
        return r.searchForward() ? new ForwardSearchTransitCalculator(s, t) : new ReverseSearchTransitCalculator(s, t);
    }

    private DebugRequest<T> debugRequest(RangeRaptorRequest<T> request) {
        return request.searchForward() ? request.debug() : request.mutate().debug().reverseDebugRequest().build();
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
