package com.conveyal.r5.analyst.network;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Model a discrete distribution and find the least-squares
 *
 * See also https://commons.apache.org/proper/commons-math/javadocs/api-3.4/org/apache/commons/math3/fitting/GaussianCurveFitter.html
 * And Histogram class.
 * Created by abyrd on 2020-11-23
 */
public class DistributionTester {

    public final double max;
    public final double min;
    public final double mean;
    public final double epsilon;

    public DistributionTester (double max, double min, double mean, double epsilon) {
        this.max = max;
        this.min = min;
        this.mean = mean;
        this.epsilon = epsilon;
    }

    public void assertWithinEpsilon (double... numbers) {
        SummaryStatistics s = new SummaryStatistics();
        for (double d : numbers) {
            s.addValue(d);
        }
        assertTrue(s.getMax() <= max);
        assertTrue(s.getMin() >= min);
        assertEquals(mean, s.getMean(), epsilon);
    }

    public static void distrib (double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
    }

    // Turn into separate class: Check that mean matches to within a given tolerance, and that min/max/quartiles are
    // exactly a given distance around that median.
    private void assertPercentiles(int min, int p25, int median, int p75, int max, double tolerance) {

    }

    public static final int[] PERCENTILES = new int[] {5, 25, 50, 75, 95};

    /**
     * Assert that the supplied array within 1 of the expected value for percentiles 5, 25, 50, 75, and 95
     * for a values uniformly distributed from the given min to max.
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
        expectedDistribution.illustrate();
        for (int p = 0; p < PERCENTILES.length; p++) {
            int expected = expectedDistribution.findPercentile(PERCENTILES[p]);
            int actual = values[p];
            assertEquals(expected, actual, 1, "Travel time off by more than one at percentile " + PERCENTILES[p]);
        }
    }

}