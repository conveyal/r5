package com.conveyal.r5.macau.distribution;

// Each distribution is the result of a state transition, so tied to a path segment.
// We could store that in the distribution, or elsewhere.
// We should have shared methods for rendering the distributions to arrays, toString, etc.
public interface Distribution {

    // All times are relative to midnight, not departure, to allow range raptor to work.
    // This all might make more sense if we treated these as discrete distributions.

    /** The lowest time in seconds after midnight at which the probability (and cumulative probability) is nonzero. */
    public int minTime ();

    /** The time in seconds after midnight at which the cumulative probability first reaches its maximum value. */
    public int maxTime ();

    /** The maximum value that the cumulative probability reaches. Usually 1 unless a service cuts off. */
    // We may want to make this always 1, but have some distribution instances return a maxTime of UNREACHABLE (max int).
    public double maxCumulativeProbability ();

    /** The amount of probability at time t. Actually since this is discrete distribution this should be a "mass" function. */
    public double probabilityDensity (int t);

    /** The probability that the random variable takes on a value less than or equal to t. */
    public double cumulativeProbability (int t);

    public default RightShift rightShift (int shiftAmount) {
        // General purpose implementation, special optimization possible when called on RightShift distributions.
        return new RightShift(this, shiftAmount);
    }

}
