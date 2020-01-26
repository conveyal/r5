package com.conveyal.r5.analyst;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

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

    private static final double EPSILON = 0.01;

    /**
     * Returns the minimum number of seconds at and above which this function will always return zero, i.e. the point
     * at which the decreasing weight curve has reached zero. Some functions approach zero asymptotically, so even with
     * a cutoff parameter of one hour they will still return tiny fractional weights all the way out to two hours
     * (or infinity). This can lead to a lot of unnecessary computation, since we need to search all the way out to 2
     * hours. It is advised to truncate the curve when the weight falls below some threshold, so we get a travel time
     * above which we can consider all opportunities unreachable.
     *
     * Contract:
     * Return units: seconds.
     * Return range: [0...7200].
     * Return null: no.
     * Side effects: no.
     * Idempotent: yes.
     * FIXME add "for a given cutoff"
     */
    public abstract int reachesZeroAt (int cutoffSeconds);

    public abstract double computeWeight (int cutoffSeconds, int travelTimeSeconds);

    public final void prepare () {
        validateAndPrecompute();
        checkInvariants();
    }

    /**
     * This method should validate any parameters supplied. Then if your function has some more efficient way of
     * generating values (e.g. precomputed tables) then it can override this method.
     * We could create a StepDecayFunction rather than treat it as a special case, in which case it would be a
     * little inefficient to generate a large array of all ones, then all zeros.
     * Likewise for linear decay, it's more memory efficient to generate a table only for the decay portion, and
     * generate the zeros and ones from . The creation of lookup tables is then left up to the implementation.
     */
    protected void validateAndPrecompute () { }

    /**
     * Verify that the function has the required characteristics.
     * We could also find the zero point here by iterating but it's simpler to just have the functions declare one.
     */
    private final void checkInvariants () {
        int cutoffSeconds = 60 * 30; // single test value, could iterate.
        int zero = reachesZeroAt(cutoffSeconds);
        if (zero < 0 || zero > TWO_HOURS_IN_SECONDS) {
            throw new AssertionError("Function provided a zero point outside 2-hour input range.");
        }
        if (Math.abs(computeWeight(cutoffSeconds, zero)) < EPSILON) {
            throw new AssertionError("Function provided a zero point that is not close to zero.");
        }
        double prevWeight = Double.POSITIVE_INFINITY;
        for (int s = 0; s <= TWO_HOURS_IN_SECONDS; s += 1) {
            double weight = computeWeight(cutoffSeconds, s);
            if (weight < 0 || weight > 1) {
                throw new AssertionError("Output range is outside [0...1].");
            }
            if (weight < prevWeight) {
                throw new AssertionError("Weight function is not monotonically decreasing.");
            }
            prevWeight = weight;
        }
    }

}
