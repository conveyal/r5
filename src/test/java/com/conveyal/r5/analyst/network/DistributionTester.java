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

}