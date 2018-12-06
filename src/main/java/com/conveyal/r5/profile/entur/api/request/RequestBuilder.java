package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
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
    int fromTime;
    int toTime;
    final Collection<AccessLeg> accessLegs = new ArrayList<>();
    final Collection<EgressLeg> egressLegs = new ArrayList<>();

    RaptorProfiles profile = RangeRaptorRequest.DEFAULTS.profile;
    int departureStepInSeconds = RangeRaptorRequest.DEFAULTS.departureStepInSeconds;
    int boardSlackInSeconds = RangeRaptorRequest.DEFAULTS.boardSlackInSeconds;
    int numberOfAdditionalTransfers = RangeRaptorRequest.DEFAULTS.numberOfAdditionalTransfers;

    final List<Integer> debugStops = new ArrayList<>();
    final List<Integer> debugPath = new ArrayList<>();
    int debugPathStartAtStopIndex = 0;
    Consumer<DebugEvent<StopArrivalView<T>>> stopArrivalListener;
    Consumer<DebugEvent<DestinationArrivalView<T>>> destinationArrivalListener;
    Consumer<DebugEvent<Path<T>>> pathFilteringListener;

    /**
     * @param fromTime See {@link RangeRaptorRequest#fromTime}
     * @param toTime See {@link RangeRaptorRequest#toTime}
     */
    public RequestBuilder(int fromTime, int toTime) {
        assertProperty(fromTime > 0, () -> "'fromTime' must be greater then 0. Value: " + fromTime);
        assertProperty(toTime > fromTime, () -> "'toTime' must be greater than 'fromTime'. fromTime: "
                + fromTime + ", toTime: " + toTime);
        this.fromTime = fromTime;
        this.toTime = toTime;
    }

    /** @see RangeRaptorRequest#profile */
    public RequestBuilder<T> profile(RaptorProfiles profile) {
        this.profile = profile;
        return this;
    }

    /**
     * Add a single access leg to the list of access legs.
     * @see RangeRaptorRequest#accessLegs */
    public RequestBuilder<T> addAccessStop(AccessLeg accessLeg) {
        this.accessLegs.add(accessLeg);
        return this;
    }

    /** @see RangeRaptorRequest#accessLegs */
    public RequestBuilder<T> addAccessStops(Iterable<AccessLeg> accessLegs) {
        for (AccessLeg it : accessLegs) {
            addAccessStop(it);
        }
        return this;
    }

    /**
     * Add a single egress leg to the list of egress legs.
     * @see RangeRaptorRequest#egressLegs
     */
    public RequestBuilder<T> addEgressStop(EgressLeg egressLeg) {
        this.egressLegs.add(egressLeg);
        return this;
    }

    /** @see RangeRaptorRequest#egressLegs */
    public RequestBuilder<T> addEgressStops(Iterable<EgressLeg> egressLegs) {
        for (EgressLeg it : egressLegs) {
            addEgressStop(it);
        }
        return this;
    }

    /** @see RangeRaptorRequest#departureStepInSeconds */
    public RequestBuilder<T> departureStepInSeconds(int departureStepInSeconds) {
        this.departureStepInSeconds = departureStepInSeconds;
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
        return new RangeRaptorRequest<>(this);
    }

    private void assertProperty(boolean predicate, Supplier<String> errorMessageProvider) {
        if(!predicate) {
            throw new IllegalArgumentException(RangeRaptorRequest.class.getSimpleName()  + " error: " + errorMessageProvider.get());
        }
    }
}
