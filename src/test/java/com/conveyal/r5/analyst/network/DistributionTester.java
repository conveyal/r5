package com.conveyal.r5.analyst.network;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Utility methods to check that distributions are similar to expected ones.
 */
public abstract class DistributionTester {

    public static final List<Integer> PERCENTILES = List.of(5, 25, 50, 75, 95);

    /**
     * Assert that the five percentiles given in the array (5, 25, 50, 75, and 95) appear to be drawn from values
     * uniformly distributed from the given min to max.
     */
    public static void assertUniformlyDistributed (int[] sortedPercentiles, int min, int max) {
        double range = max - min;
        for (int p = 0; p < PERCENTILES.size(); p++) {
            double percentile = PERCENTILES.get(p);
            double expected = min + (percentile * range / 100d);
            double actual = sortedPercentiles[p];
            assertEquals(expected, actual, 1, "Travel time off by more than one at percentile " + percentile);
        }
    }

    public static void assertExpectedDistribution (Distribution expectedDistribution, int[] values) {
        for (int p = 0; p < PERCENTILES.size(); p++) {
            int expected = expectedDistribution.findPercentile(PERCENTILES.get(p));
            int actual = values[p];
            assertEquals(expected, actual, 1, "Travel time off by more than one at percentile " + PERCENTILES.get(p));
        }
    }

}
