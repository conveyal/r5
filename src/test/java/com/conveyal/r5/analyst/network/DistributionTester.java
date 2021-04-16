package com.conveyal.r5.analyst.network;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Utility methods to check that distributions are similar to expected ones.
 */
public abstract class DistributionTester {

    public static final int[] PERCENTILES = new int[] {5, 25, 50, 75, 95};

    /**
     * Assert that the five percentiles given in the array (5, 25, 50, 75, and 95) appear to be drawn from values
     * uniformly distributed from the given min to max.
     */
    public static void assertUniformlyDistributed (int[] sortedPercentiles, int min, int max) {
        double range = max - min;
        for (int p = 0; p < PERCENTILES.length; p++) {
            double percentile = PERCENTILES[p];
            double expected = min + (percentile * range / 100d);
            double actual = sortedPercentiles[p];
            assertEquals(expected, actual, 1, "Travel time off by more than one at percentile " + percentile);
        }
    }

    public static void assertExpectedDistribution (Distribution expectedDistribution, int[] values) {
        for (int p = 0; p < PERCENTILES.length; p++) {
            int expected = expectedDistribution.findPercentile(PERCENTILES[p]);
            int actual = values[p];
            assertEquals(expected, actual, 1, "Travel time off by more than one at percentile " + PERCENTILES[p]);
        }
    }

}
