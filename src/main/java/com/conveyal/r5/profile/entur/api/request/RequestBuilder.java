package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * This is a Request builder to help construct valid requests. Se the
 * request classes for documentation on each parameter.
 * <p/>
 * <ul>
 *     <li>{@link RangeRaptorRequest}
 *     <li>{@link MultiCriteriaCostFactors}
 *     <li>{@link DebugRequest}
 * </ul>
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RequestBuilder<T extends TripScheduleInfo> {
    // Search
    private int earliestDepartureTime;
    private int latestArrivalTime;
    private int searchWindowInSeconds;
    private boolean arrivedBy;
    private final Collection<TransferLeg> accessLegs;
    private final Collection<TransferLeg> egressLegs;
    private int boardSlackInSeconds;
    private int numberOfAdditionalTransfers;

    // Algorithm
    private RaptorProfile profile;
    private int multiCriteriaBoardCost;
    private int multiCriteriaWalkReluctanceFactor;
    private int multiCriteriaWaitReluctanceFactor;

    // Debug
    private final List<Integer> debugStops;
    private final List<Integer> debugPath;
    private int debugPathStartAtStopIndex;
    private Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener;
    private Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener;
    private Consumer<DebugEvent<Path<T>>> pathFilteringListener;



    public RequestBuilder() {
        this(RangeRaptorRequest.defaults(), MultiCriteriaCostFactors.DEFAULTS);
    }

    public RequestBuilder(RangeRaptorRequest<T> defaults, MultiCriteriaCostFactors mcDefaults) {
        this.earliestDepartureTime = defaults.earliestDepartureTime();
        this.latestArrivalTime = defaults.latestArrivalTime();
        this.accessLegs = new ArrayList<>();
        this.egressLegs = new ArrayList<>();
        this.boardSlackInSeconds = defaults.boardSlackInSeconds();
        this.numberOfAdditionalTransfers = defaults.numberOfAdditionalTransfers();

        // Algorithm
        this.profile = defaults.profile();
        this.multiCriteriaBoardCost = mcDefaults.boardCost();
        this.multiCriteriaWalkReluctanceFactor = mcDefaults.walkReluctanceFactor();
        this.multiCriteriaWaitReluctanceFactor = mcDefaults.waitReluctanceFactor();

        // Debug
        this.debugStops = new ArrayList<>();
        this.debugPath = new ArrayList<>();
        this.debugPathStartAtStopIndex = 0;
        this.stopArrivalListener = null;
        this.destinationArrivalListener = null;
        this.pathFilteringListener = null;
    }

    public RaptorProfile profile() {
        return profile;
    }

    public RequestBuilder<T> profile(RaptorProfile profile) {
        this.profile = profile;
        return this;
    }

    public Collection<TransferLeg> accessLegs() {
        return accessLegs;
    }

    public RequestBuilder<T> addAccessStop(TransferLeg accessLeg) {
        this.accessLegs.add(accessLeg);
        return this;
    }

    public RequestBuilder<T> addAccessStops(Iterable<TransferLeg> accessLegs) {
        for (TransferLeg it : accessLegs) {
            addAccessStop(it);
        }
        return this;
    }

    public Collection<TransferLeg> egressLegs() {
        return egressLegs;
    }

    public RequestBuilder<T> addEgressStop(TransferLeg egressLeg) {
        this.egressLegs.add(egressLeg);
        return this;
    }

    public RequestBuilder<T> addEgressStops(Iterable<TransferLeg> egressLegs) {
        for (TransferLeg it : egressLegs) {
            addEgressStop(it);
        }
        return this;
    }

    public int earliestDepartureTime() {
        return earliestDepartureTime;
    }

    public RequestBuilder<T> earliestDepartureTime(int earliestDepartureTime) {
        this.earliestDepartureTime = earliestDepartureTime;
        return this;
    }

    public int latestArrivalTime() {
        return latestArrivalTime;
    }

    public RequestBuilder<T> latestArrivalTime(int latestArrivalTime) {
        this.latestArrivalTime = latestArrivalTime;
        return this;
    }

    public int searchWindowInSeconds() {
        return searchWindowInSeconds;
    }

    public RequestBuilder<T> searchWindowInSeconds(int searchWindowInSeconds) {
        this.searchWindowInSeconds = searchWindowInSeconds;
        return this;
    }

    public boolean arrivedBy() {
        return arrivedBy;
    }

    public RequestBuilder<T> arrivedBy(boolean arrivedBy) {
        this.arrivedBy = arrivedBy;
        return this;
    }

    public int boardSlackInSeconds() {
        return boardSlackInSeconds;
    }

    public RequestBuilder<T> boardSlackInSeconds(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
        return this;
    }


    public int numberOfAdditionalTransfers() {
        return numberOfAdditionalTransfers;
    }

    public RequestBuilder<T> numberOfAdditionalTransfers(int numberOfAdditionalTransfers) {
        this.numberOfAdditionalTransfers = numberOfAdditionalTransfers;
        return this;
    }

    public int multiCriteriaBoardCost() {
        return multiCriteriaBoardCost;
    }

    public RequestBuilder<T> multiCriteriaBoardCost(int boardCost) {
        this.multiCriteriaBoardCost = boardCost;
        return this;
    }

    public int multiCriteriaWalkReluctanceFactor() {
        return multiCriteriaWalkReluctanceFactor;
    }

    public RequestBuilder<T> multiCriteriaWalkReluctanceFactor(int walkReluctanceFactor) {
        this.multiCriteriaWalkReluctanceFactor = walkReluctanceFactor;
        return this;
    }

    public int multiCriteriaWaitReluctanceFactor() {
        return multiCriteriaWaitReluctanceFactor;
    }

    public RequestBuilder<T> multiCriteriaWaitReluctanceFactor(int waitReluctanceFactor) {
        this.multiCriteriaWaitReluctanceFactor = waitReluctanceFactor;
        return this;
    }

    public List<Integer> debugStops() {
        return debugStops;
    }

    public RequestBuilder<T> debugStop(int stop) {
        this.debugStops.add(stop);
        return this;
    }

    public List<Integer> debugPath() {
        return debugPath;
    }

    public RequestBuilder<T> debugPath(List<Integer> path) {
        this.debugPath.clear();
        this.debugPath.addAll(path);
        return this;
    }

    public int debugPathStartAtStopIndex() {
        return debugPathStartAtStopIndex;
    }

    public RequestBuilder<T> debugPathStartAtStopIndex(Integer debugPathStartAtStopIndex) {
        this.debugPathStartAtStopIndex = debugPathStartAtStopIndex;
        return this;
    }

    public Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener() {
        return stopArrivalListener;
    }

    public RequestBuilder<T> stopArrivalListener(Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener) {
        this.stopArrivalListener = stopArrivalListener;
        return this;
    }

    public Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener() {
        return destinationArrivalListener;
    }

    public RequestBuilder<T> destinationArrivalListener(Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener) {
        this.destinationArrivalListener = destinationArrivalListener;
        return this;
    }

    public Consumer<DebugEvent<Path<T>>> pathFilteringListener() {
        return pathFilteringListener;
    }

    public RequestBuilder<T> pathFilteringListener(Consumer<DebugEvent<Path<T>>> pathFilteringListener) {
        this.pathFilteringListener = pathFilteringListener;
        return this;
    }

    public RangeRaptorRequest<T> build() {
        assertProperty(!accessLegs.isEmpty(), () ->"At least one 'accessLegs' is required.");
        assertProperty(!egressLegs.isEmpty(), () ->"At least one 'egressLegs' is required.");
        return new RangeRaptorRequest<>(this);
    }

    public DebugRequest<T> debug() {
        return new DebugRequest<>(this);
    }

    private void assertProperty(boolean predicate, Supplier<String> errorMessageProvider) {
        if(!predicate) {
            throw new IllegalArgumentException(RangeRaptorRequest.class.getSimpleName()  + " error: " + errorMessageProvider.get());
        }
    }
}
