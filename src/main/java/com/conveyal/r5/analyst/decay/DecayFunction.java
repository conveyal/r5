package com.conveyal.r5.analyst.decay;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A family of monotonically decreasing functions from travel times to weight factors in the range [0...1].
 * This determines how much an opportunity at a given travel time is weighted when included in an accessibility value.
 * It has at least one parameter, the "cutoff" which is the midpoint of the decrease from one to zero.
 * Some subclasses will have an additional parameter that essentially adjusts the width of the transition region.
 * Like modifications, these are deserialized from JSON and then actually used in the computations.
 * TODO redefine so that cutoff is supplied at construction, if precomputation is too slow?
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
    @JsonSubTypes.Type(name="step", value= StepDecayFunction.class),
    @JsonSubTypes.Type(name="linear", value=LinearDecayFunction.class),
    @JsonSubTypes.Type(name="logistic", value=LogisticDecayFunction.class),
    @JsonSubTypes.Type(name="exponential", value=ExponentialDecayFunction.class),
    @JsonSubTypes.Type(name="fixed-exponential", value=FixedExponentialDecayFunction.class)
})
public abstract class DecayFunction {

    public static final int TWO_HOURS_IN_SECONDS = 2 * 60 * 60;

    public static final int FOUR_HOURS_IN_SECONDS = 4 * 60 * 60;

    protected static final double ZERO_EPSILON = 0.001;

    /**
     * For a given cutoff, returns the minimum travel time at or beyond which this function will always return a zero
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
    public int reachesZeroAt (int cutoffSeconds) {
        return findZeroPoint(cutoffSeconds);
    }

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
     * It should validate any parameters supplied via JSON into instance fields.
     * If a DecayFunction implementation has some more efficient way of generating values (e.g. precomputed tables)
     * then it can also do any required precomputation in this method.
     */
    public abstract void prepare ();

    /**
     * For functions without a simple analytic solution, find the effective zero point by bisection.
     * We require functions to reach zero at or above the cutoff point, but before four hours.
     * Adapted from Python's bisect_right function. Quick benchmarks show effective search time is under one msec.
     */
    private int findZeroPoint (int cutoffSeconds) {
        int low = cutoffSeconds;
        int high = 4 * 60 * 60;
        while (low < high) {
            int mid = (low + high) / 2;
            double weight = computeWeight(cutoffSeconds, mid);
            if (weight < ZERO_EPSILON) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }
}
