package com.conveyal.r5.profile.entur.api.request;

import com.conveyal.r5.profile.entur.api.transit.AccessLeg;
import com.conveyal.r5.profile.entur.api.transit.EgressLeg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * This is a Request builder to help construct valid requests.
 */
public class RequestBuilder {
    int fromTime;
    int toTime;
    final Collection<AccessLeg> accessLegs = new ArrayList<>();
    final Collection<EgressLeg> egressLegs = new ArrayList<>();

    RaptorProfiles profile = RangeRaptorRequest.DEFAULTS.profile;
    int departureStepInSeconds = RangeRaptorRequest.DEFAULTS.departureStepInSeconds;
    int boardSlackInSeconds = RangeRaptorRequest.DEFAULTS.boardSlackInSeconds;
    int numberOfAdditionalTransfers = RangeRaptorRequest.DEFAULTS.numberOfAdditionalTransfers;

    public RequestBuilder(int fromTime, int toTime) {
        assertProperty(fromTime > 0, () -> "'fromTime' must be greater then 0. Value: " + fromTime);
        assertProperty(toTime > fromTime, () -> "'toTime' must be greater than 'fromTime'. fromTime: "
                + fromTime + ", toTime: " + toTime);
        this.fromTime = fromTime;
        this.toTime = toTime;
    }

    public RequestBuilder profile(RaptorProfiles profile) {
        this.profile = profile;
        return this;
    }

    public RequestBuilder addAccessStop(AccessLeg accessLeg) {
        this.accessLegs.add(accessLeg);
        return this;
    }

    public RequestBuilder addAccessStops(Iterable<AccessLeg> accessLegs) {
        for (AccessLeg it : accessLegs) {
            addAccessStop(it);
        }
        return this;
    }

    public RequestBuilder addEgressStop(EgressLeg egressLeg) {
        this.egressLegs.add(egressLeg);
        return this;
    }

    public RequestBuilder addEgressStops(Iterable<EgressLeg> egressLegs) {
        for (EgressLeg it : egressLegs) {
            addEgressStop(it);
        }
        return this;
    }

    public RequestBuilder departureStepInSeconds(int departureStepInSeconds) {
        this.departureStepInSeconds = departureStepInSeconds;
        return this;
    }

    public RequestBuilder boardSlackInSeconds(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
        return this;
    }

    public RequestBuilder numberOfAdditionalTransfers(int numberOfAdditionalTransfers) {
        this.numberOfAdditionalTransfers = numberOfAdditionalTransfers;
        return this;
    }

    public RangeRaptorRequest build() {
        assertProperty(!accessLegs.isEmpty(), () ->"At least one 'accessLegs' is required.");
        assertProperty(!egressLegs.isEmpty(), () ->"At least one 'egressLegs' is required.");
        return new RangeRaptorRequest(this);
    }

    private void assertProperty(boolean predicate, Supplier<String> errorMessageProvider) {
        if(!predicate) {
            throw new IllegalArgumentException(RangeRaptorRequest.class.getSimpleName()  + " error: " + errorMessageProvider.get());
        }
    }
}
