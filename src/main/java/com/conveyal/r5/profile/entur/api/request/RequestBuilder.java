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
 * This is a Request builder to help construct valid requests.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class RequestBuilder<T extends TripScheduleInfo> {
    // Search
    int fromTime;
    int toTime;
    final Collection<TransferLeg> accessLegs;
    final Collection<TransferLeg> egressLegs;
    int boardSlackInSeconds;
    int numberOfAdditionalTransfers;

    // Algorithm
    RaptorProfile profile;

    // Debug
    final List<Integer> debugStops;
    final List<Integer> debugPath;
    int debugPathStartAtStopIndex;
    Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener;
    Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener;
    Consumer<DebugEvent<Path<T>>> pathFilteringListener;

    /**
     * @param fromTime See {@link RangeRaptorRequest#fromTime}
     * @param toTime See {@link RangeRaptorRequest#toTime}
     */
    public RequestBuilder(int fromTime, int toTime) {
        this(fromTime, toTime, createDefaults());
    }

    public RequestBuilder(int fromTime, int toTime, RangeRaptorRequest<T> defaults) {
        assertProperty(fromTime > 0, () -> "'fromTime' must be greater then 0. Value: " + fromTime);
        assertProperty(toTime > fromTime, () -> "'toTime' must be greater than 'fromTime'. fromTime: "
                + fromTime + ", toTime: " + toTime);
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.accessLegs = new ArrayList<>();
        this.egressLegs = new ArrayList<>();
        this.boardSlackInSeconds = defaults.boardSlackInSeconds();
        this.numberOfAdditionalTransfers = defaults.numberOfAdditionalTransfers();

        // Algorithm
        this.profile = defaults.profile();

        // Debug
        this.debugStops = new ArrayList<>();
        this.debugPath = new ArrayList<>();
        this.debugPathStartAtStopIndex = 0;
        this.stopArrivalListener = null;
        this.destinationArrivalListener = null;
        this.pathFilteringListener = null;
    }

    /** @see RangeRaptorRequest#profile */
    public RequestBuilder<T> profile(RaptorProfile profile) {
        this.profile = profile;
        return this;
    }

    /**
     * Add a single access leg to the list of access legs.
     * @see RangeRaptorRequest#accessLegs */
    public RequestBuilder<T> addAccessStop(TransferLeg accessLeg) {
        this.accessLegs.add(accessLeg);
        return this;
    }

    /** @see RangeRaptorRequest#accessLegs */
    public RequestBuilder<T> addAccessStops(Iterable<TransferLeg> accessLegs) {
        for (TransferLeg it : accessLegs) {
            addAccessStop(it);
        }
        return this;
    }

    /**
     * Add a single egress leg to the list of egress legs.
     * @see RangeRaptorRequest#egressLegs
     */
    public RequestBuilder<T> addEgressStop(TransferLeg egressLeg) {
        this.egressLegs.add(egressLeg);
        return this;
    }

    /** @see RangeRaptorRequest#egressLegs */
    public RequestBuilder<T> addEgressStops(Iterable<TransferLeg> egressLegs) {
        for (TransferLeg it : egressLegs) {
            addEgressStop(it);
        }
        return this;
    }

    /** @see RangeRaptorRequest#boardSlackInSeconds */
    public RequestBuilder<T> boardSlackInSeconds(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
        return this;
    }

    /** @see RangeRaptorRequest#numberOfAdditionalTransfers */
    public RequestBuilder<T> numberOfAdditionalTransfers(int numberOfAdditionalTransfers) {
        this.numberOfAdditionalTransfers = numberOfAdditionalTransfers;
        return this;
    }

    /**
     * Add a stop the stops list.
     * @see DebugRequest#stops()
     */
    public RequestBuilder<T> debugStop(int stop) {
        this.debugStops.add(stop);
        return this;
    }

    /** @see DebugRequest#path() */
    public RequestBuilder<T> debugPath(List<Integer> path) {
        this.debugPath.clear();
        this.debugPath.addAll(path);
        return this;
    }

    /** @see DebugRequest#pathStartAtStopIndex() */
    public RequestBuilder<T> debugPathStartAtStopIndex(Integer debugPathStartAtStopIndex) {
        this.debugPathStartAtStopIndex = debugPathStartAtStopIndex;
        return this;
    }

    /** @see DebugRequest#stopArrivalListener() */
    public RequestBuilder<T> stopArrivalListener(Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener) {
        this.stopArrivalListener = stopArrivalListener;
        return this;
    }

    /** @see DebugRequest#destinationArrivalListener() */
    public RequestBuilder<T> destinationArrivalListener(Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener) {
        this.destinationArrivalListener = destinationArrivalListener;
        return this;
    }

    /** @see DebugRequest#pathFilteringListener()  */
    public RequestBuilder<T> pathFilteringListener(Consumer<DebugEvent<Path<T>>> pathFilteringListener) {
        this.pathFilteringListener = pathFilteringListener;
        return this;
    }

    public RangeRaptorRequest<T> build() {
        assertProperty(!accessLegs.isEmpty(), () ->"At least one 'accessLegs' is required.");
        assertProperty(!egressLegs.isEmpty(), () ->"At least one 'egressLegs' is required.");
        return new RequestObject<>(this);
    }

    public DebugRequest<T> debug() {
        return new DebugRequestObject<>(this);
    }

    public RaptorProfile profile() {
        return profile;
    }

    private void assertProperty(boolean predicate, Supplier<String> errorMessageProvider) {
        if(!predicate) {
            throw new IllegalArgumentException(RangeRaptorRequest.class.getSimpleName()  + " error: " + errorMessageProvider.get());
        }
    }

    private static<T extends TripScheduleInfo> RangeRaptorRequest<T> createDefaults() {
        return new RangeRaptorRequest<T>() {
            @Override public int fromTime() { return -1; }
            @Override public int toTime() { return -1; }
        };
    }
}
