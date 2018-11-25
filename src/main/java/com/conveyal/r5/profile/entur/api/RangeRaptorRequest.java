package com.conveyal.r5.profile.entur.api;

import com.conveyal.r5.profile.entur.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;


/**
 * All input parameters to RangeRaptor that is spesific to a routing request.
 * See {@link TransitDataProvider} for transit data.
 */
public class RangeRaptorRequest {

    /**
     * Default values
     * @see #RangeRaptorRequest() for property default values.
     */
    private static final RangeRaptorRequest DEFAULTS = new RangeRaptorRequest();

    private static final int NOT_SET = -1;


    /** The profile/algorithm to use for this request. */
    public final RaptorProfiles profile;

    /** The beginning of the departure window, in seconds since midnight. */
    public final int fromTime;

    /** The end of the departure window, in seconds since midnight. */
    public final int toTime;

    /** Times to access each transit stop using the street network in seconds. */
    public final Collection<AccessLeg> accessLegs;

    /**
     * List of all possible egress stops and time to reach destination in seconds.
     * <p>
     * NOTE! The {@link EgressLeg#stop()} is the stop where the egress leg
     * start, NOT the destination - think of it as a reversed leg.
     */
    public final Collection<EgressLeg> egressLegs;

    /**
     * Step for departure times between each RangeRaptor iterations.
     * This is a performance optimization parameter.
     * A transit network usually uses minute resolution for the its timetable,
     * so to match that set this variable to 60 seconds. Setting it
     * to less then 60 will not give better result, but degrade performance.
     * Setting it to 120 seconds will improve performance, but you might get a
     * slack of 60 seconds somewhere in the result - most likely in the first
     * walking leg.
     */
    public final int departureStepInSeconds;

    /**
     * The minimum wait time for transit boarding to account for schedule variation.
     * This is added between transits, between transfer and transit, and between access "walk" and transit.
     */
    public final int boardSlackInSeconds;


    /**
     * RangeRaptor is designed to search until the destination is reached and then
     * {@code numberOfAdditionalTransfers} more rounds.
     *
     * TODO TGR - Implement this feature.
     */
    public final int numberOfAdditionalTransfers;


    /**
     * Intentionally private default constructor, which only serve as a place
     * to define default values.
     */
    private RangeRaptorRequest() {
        // Required parameters
        this.fromTime = NOT_SET;
        this.toTime = NOT_SET;
        this.accessLegs = Collections.emptyList();
        this.egressLegs = Collections.emptyList();

        // Optional parameters with default values
        this.profile = RaptorProfiles.MULTI_CRITERIA_RANGE_RAPTOR;
        this.departureStepInSeconds = 60;
        this.boardSlackInSeconds = 60;
        this.numberOfAdditionalTransfers = 3;
    }

    private RangeRaptorRequest(Builder builder) {
        this.profile = builder.profile;
        this.fromTime = builder.fromTime;
        this.toTime = builder.toTime;
        this.accessLegs = builder.accessLegs;
        this.egressLegs = builder.egressLegs;
        this.departureStepInSeconds = builder.departureStepInSeconds;
        this.boardSlackInSeconds = builder.boardSlackInSeconds;
        this.numberOfAdditionalTransfers = builder.numberOfAdditionalTransfers;
    }


    /**
     * Compute number of Range Raptor iterations for scheduled search
     */
    int nMinutes() {
        return  (toTime - fromTime) / departureStepInSeconds;
    }

    @Override
    public String toString() {
        return "RangeRaptorRequest{" +
                "from=" + TimeUtils.timeToStrLong(fromTime) +
                ", toTime=" + TimeUtils.timeToStrLong(toTime) +
                ", accessLegs=" + accessLegs +
                ", egressLegs=" + egressLegs +
                ", departureStepInSeconds=" + departureStepInSeconds +
                ", boardSlackInSeconds=" + boardSlackInSeconds +
                '}';
    }

    public static class Builder {
        private int fromTime;
        private int toTime;
        private final Collection<AccessLeg> accessLegs = new ArrayList<>();
        private final Collection<EgressLeg> egressLegs = new ArrayList<>();

        private RaptorProfiles profile = DEFAULTS.profile;
        private int departureStepInSeconds = DEFAULTS.departureStepInSeconds;
        private int boardSlackInSeconds = DEFAULTS.boardSlackInSeconds;
        private int numberOfAdditionalTransfers = DEFAULTS.numberOfAdditionalTransfers;

        public Builder(int fromTime, int toTime) {
            assertProperty(fromTime > 0, () -> "'fromTime' must be greater then 0. Value: " + fromTime);
            assertProperty(toTime > fromTime, () -> "'toTime' must be greater than 'fromTime'. fromTime: "
                    + fromTime + ", toTime: " + toTime);
            this.fromTime = fromTime;
            this.toTime = toTime;
        }

        public Builder profile(RaptorProfiles profile) {
            this.profile = profile;
            return this;
        }

        public Builder addAccessStop(AccessLeg accessLeg) {
            this.accessLegs.add(accessLeg);
            return this;
        }

        public Builder addAccessStops(Iterable<AccessLeg> accessLegs) {
            for (AccessLeg it : accessLegs) {
                addAccessStop(it);
            }
            return this;
        }

        public Builder addEgressStop(EgressLeg egressLeg) {
            this.egressLegs.add(egressLeg);
            return this;
        }

        public Builder addEgressStops(Iterable<EgressLeg> egressLegs) {
            for (EgressLeg it : egressLegs) {
                addEgressStop(it);
            }
            return this;
        }

        public Builder departureStepInSeconds(int departureStepInSeconds) {
            this.departureStepInSeconds = departureStepInSeconds;
            return this;
        }

        public Builder boardSlackInSeconds(int boardSlackInSeconds) {
            this.boardSlackInSeconds = boardSlackInSeconds;
            return this;
        }

        public Builder numberOfAdditionalTransfers(int numberOfAdditionalTransfers) {
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
}