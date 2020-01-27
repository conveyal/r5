package com.conveyal.r5.analyst;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import static com.google.common.base.Preconditions.checkState;

/**
 * A family of monotonically decreasing functions from travel times to weight factors in the range [0...1].
 * This determines how much an opportunity at a given travel time is weighted when included in an accessibility value.
 * It has at least one parameter, the "cutoff" which is the midpoint of the decrease from one to zero.
 * Some subclasses will have an additional parameter that essentially adjusts the width of the transition region.
 * Like modifications, these are deserialized from JSON and then actually used in the computations.
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
    @JsonSubTypes.Type(name="step", value=StepDecayFunction.class),
    @JsonSubTypes.Type(name="linear", value=LinearDecayFunction.class),
    @JsonSubTypes.Type(name="exponential", value= ExponentialDecayFunction.class),
    @JsonSubTypes.Type(name="sigmoid", value=SigmoidDecayFunction.class),
})
public abstract class DecayFunction {

    public static final int TWO_HOURS_IN_SECONDS = 2 * 60 * 60;

    private static final double EPSILON = 0.0001;

    /**
     * For a given cutoff, returns the minimum travel time at or beyong which this function will always return a zero
     * weight, i.e. the point at which the decreasing weight curve has reached zero. Some functions approach zero
     * asymptotically, so they will still return tiny fractional weights all the way out to two hours (or infinity).
     * This can lead to a lot of unnecessary computation, causing us to search all the way out to 2 hours. It is
     * advisable for decay function implemetations truncate the curve when the weight falls below some threshold, so we
     * get a small finite travel time above which we can consider all opportunities unreachable.
     *
     * Contract:
     * Input units: seconds
     * Input range: [0...7200]
     * Return units: seconds
     * Return range: [0...7200]
     * Idempotent, no side effects.
     */
    public abstract int reachesZeroAt (int cutoffSeconds);

    /**
     * The DelayFunction subclasses should supply implementations of this method to return the weight factor for
     * opportunities situated at a given travel time away from the origin, for a given cutoff parameter.
     *
     * Contract:
     * Input units: seconds (for both inputs)
     * Input range: [0...7200]
     * Return units: opportunities
     * Return range: [0...1]
     * Idempotent, no side effects.
     * Invariant: for any fixed cutoff, increasing travel time decreases output weight (monotonically decrteasing)
     */
    public abstract double computeWeight (int cutoffSeconds, int travelTimeSeconds);

    /**
     * Call this method on a deserialized DecayFunction to prepare it for use.
     */
    public final void prepare () {
        validateParameters();
        precompute();
        checkInvariants();
    }

    /**
     * This method is supplied by the concrete DecayFunction subtype.
     * It should validate any parameters supplied via JSON into instance fields.
     */
    protected void validateParameters () { }


    /**
     * If a DecayFunction implementation has some more efficient way of generating values (e.g. precomputed tables)
     * then it can do any required precomputation in this method.
     */
    protected void precompute () { }

    /**
     * Verify that the function has the expected characteristics.
     * We could also find the zero point here by iterating but it's simpler to just have the functions declare one.
     */
    private void checkInvariants () {
        // Try a few values, iterating over all 7200 would be too slow.
        for (int cutoffMinutes : new int[] {9, 34, 61}) {
            int cutoffSeconds = cutoffMinutes * 60;
            int zero = reachesZeroAt(cutoffSeconds);
            // Maybe it should not be the responsibility of the function to clamp outputs, negative values are OK
            checkState(zero > 0, "Decay function zero point must be positive.");
            checkState(zero < TWO_HOURS_IN_SECONDS, "Decay function zero point must be two hours or less.");
            checkState(zero >= cutoffMinutes, "Zero point should be at or above cutoff.");
            double zeroValue = Math.abs(computeWeight(cutoffSeconds, zero));
            checkState(zeroValue < EPSILON, "Decay function output for zero point must be close to zero.");
            double almostZeroValue = Math.abs(computeWeight(cutoffSeconds, zero - 1));
            checkState(almostZeroValue >= EPSILON, "Zero point must be the smallest input whose output is close to zero.");
            double prevWeight = Double.POSITIVE_INFINITY;
            for (int s = 0; s <= TWO_HOURS_IN_SECONDS; s += 1) {
                double weight = computeWeight(cutoffSeconds, s);
                checkState(weight >= 0, "Weight output must be non-negative.");
                checkState(weight <= 1, "Weight output must not exceed one.");
                checkState(weight <= prevWeight, "Weight function must be monotonically decreasing.");
                prevWeight = weight;
            }
        }
    }

}
