package com.conveyal.r5.analyst.network;

import com.conveyal.r5.analyst.cluster.TravelTimeResult;
import com.google.common.base.Preconditions;

import java.util.Arrays;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Used to model expected travel times in testing, for comparison against actual times reported by the routing system.
 * Designed to facilitate convolution to find the distribution of a sum of random variables,
 * modeling successive waits for pure frequency routes or waits for scheduled routes given a departure time distribution.
 */
public class Distribution {

    // For debugging and test development: display a chart in a window every time two distributions are compared.
    private static final boolean SHOW_CHARTS = false;

    private int skip;
    private double[] masses; // impulse response

    private Distribution () {};

    public Distribution copy () {
        Distribution result = new Distribution();
        result.skip = skip;
        result.masses = masses;
        return result;
    }

    public Distribution delay (int amount) {
        Distribution result = this.copy();
        result.skip += amount;
        return result;
    }

    public Distribution (int skip, int width) {
        this.skip = skip;
        masses = new double[width];
        Arrays.fill(masses, 1.0/width);
    }

    public static Distribution convolution (Distribution... inputs) {
        Distribution output = null;
        for (Distribution d : inputs) {
            if (output == null) {
                output = d;
            } else {
                output = convolution(output, d);
            }
        }
        return output;
    }

    public static Distribution convolution (
            Distribution a,
            Distribution b
    ) {
        Distribution result = new Distribution();
        result.skip = a.skip + b.skip;
        // Results only exist where the two signals overlap, shortening the output by one.
        result.masses = new double[a.masses.length + b.masses.length - 1];
        for (int i = 0; i < a.masses.length; i++) {
            double aMass = a.masses[i];
            for (int j = 0; j < b.masses.length; j++) {
                double bMass = b.masses[j];
                result.masses[i + j] += (aMass * bMass);
            }
        }
        // The total mass should already be very close to 1 but make it as close as we can.
        result.normalize();
        return result;
    }

    public double totalMass () {
        double totalMass = 0;
        for (int i = 0; i < masses.length; i++) {
            totalMass += masses[i];
        }
        return totalMass;
    }

    public void normalize () {
        double totalMass = this.totalMass();
        if (totalMass == 0 || totalMass == 1) {
            return;
        }
        for (int i = 0; i < masses.length; i++) {
            masses[i] /= totalMass;
        }
    }

    /**
     * Print a text-based representation of the distribution to standard out.
     * There is another method to show the distribution in a graphical plot window.
     */
    public void illustrate () {
        final int width = 50;
        double max = Arrays.stream(masses).max().getAsDouble();
        double scale = width / max;
        for (int i = 0; i < fullWidth(); i++) {
            System.out.printf("%3d %s\n", i, "=".repeat((int)(probabilityOf(i) * scale)));
        }
        for (int percentile : new int[] {5, 25, 50, 75, 95}) {
            System.out.printf("Pecentile %2d at x=%d\n", percentile, findPercentile(percentile));
        }
    }

    /**
     * Given a percentile such as 25 or 50, find the x bin at which that percentile is situated in this Distribution,
     * i.e. the lowest (binned or discretized) x value for which the cumulative probability is at least percentile.
     * In common usage: find the lowest whole-minute travel time for which the cumulative probability is greater than
     * the supplied percentile.
     */
    public int findPercentile (int percentile) {
        double sum = 0;
        double threshold = percentile / 100d;
        for (int i = 0; i < masses.length; i++) {
            sum += masses[i];
            if (sum >= threshold) {
                return skip + i;
            }
        }
        return skip + masses.length;
    }

    public static void main (String[] args) {
        Distribution a = new Distribution(2, 10);
        Distribution b = new Distribution(2, 5);
        Distribution c = new Distribution(2, 20);
        Distribution d = new Distribution(2, 8);
        Distribution out = convolution(a, b, c, d);
        out.illustrate();
    }

    /**
     * @return the probability mass situated at a particular x value (the probability density for a particular minute
     *         when these are used in the usual way as 1-minute bins).
     */
    public double probabilityOf (int x) {
        if (x < skip) {
            return 0;
        }
        int m = x - skip;
        if (m < masses.length) {
            return masses[m];
        } else {
            return 0;
        }
    }

    public int fullWidth () {
        return skip + masses.length;
    }

    public int maxIndex () {
        return fullWidth() - 1;
    }

    public Distribution or (Distribution other) {
        return or(this, other);
    }

    public static Distribution or (Distribution... others) {
        Distribution result = null;
        for (Distribution other : others) {
            if (result == null) {
                result = other;
            } else {
                result = result.or(other);
            }
        }
        return result;
    }

    /**
     * Find the probability at each time that one of a or b arrives for the first time.
     */
    public static Distribution or (Distribution a, Distribution b) {
        Distribution result = new Distribution();
        result.skip = Math.min(a.skip, b.skip);
        // The CDF of this combination reaches 1 where either input's CDF reaches 1.
        int resultFullWidth = Math.min(a.fullWidth(), b.fullWidth());
        result.masses = new double[resultFullWidth - result.skip];
        double aCumulative = 0;
        double bCumulative = 0;
        for (int m = 0; m < result.masses.length; m++) {
            int i = m + result.skip;
            double pa = a.probabilityOf(i);
            double pb = b.probabilityOf(i);
            double aBeatsB = pa * (1-bCumulative); // a arrives and b has not yet arrived
            double bBeatsA = pb * (1-aCumulative); // b arrives and a has not yet arrived
            // (a arrives and b hasn't) or (b arrives and a hasn't) considering they can both arrive at the same time.
            result.masses[m] = aBeatsB + bBeatsA - (pa * pb);
            aCumulative += pa;
            bCumulative += pb;
        }
        // result.normalize();
        return result;
    }


    public static Distribution fromTravelTimeResult (TravelTimeResult travelTimeResult, int point) {
        Distribution result = new Distribution();
        int[] counts = travelTimeResult.getHistogram(point);
        result.skip = 0;
        result.masses = new double[counts.length];
        for (int i = 0; i < 120; i++) {
            result.masses[i] = counts[i];
        }
        result.normalize();
        result.trim();
        return result;
    }

    /**
     * Find the probability mass of the overlapping region of the two distributions. This can be used to determine
     * whether two distributions, often a theoretical one and an observed one, are sufficiently similar to one another.
     * Overlapping here means in both dimensions, travel time (horizontal) and probability density (vertical).
     * Proceeding bin by bin through both distributions in parallel, the smaller of the two values for each bin is
     * accumulated into the total. The amount of "misplaced" probability (located in the wrong bin in the observed
     * distribution relative to the theoretical one) is one minus overlap. Overlap is slightly more straightforward
     * to calculate directly than mismatch. This method is not sensitive to how evenly the error is distributed
     * across the domain. We should prefer using a measure that emphasizes larger errors and compensates for the
     * magnitude of the predicted values.
     */
    public double overlap (Distribution other) {
        // TODO This min is not necessary. The overlap is by definition fully within the domain of either Distribution.
        int iMin = Math.min(this.skip, other.skip);
        int iMax = Math.max(this.fullWidth(), other.fullWidth());
        double sum = 0;
        for (int i = iMin; i < iMax; i++) {
            double pa = this.probabilityOf(i);
            double pb = other.probabilityOf(i);
            sum += Math.min(pa, pb);
        }
        System.out.println("Overlap: " + sum);
        return sum;
    }

    /**
     * An ad-hoc measure of goodness of fit vaguely related to Pearson's chi-squared or root-mean-square error.
     * Traditional measures used in fitting probability distributions like Pearson's have properties that deal poorly
     * with our need to tolerate small horizontal shifts
     * in the results (due to the destination grid being not precisely aligned with our street corner grid).
     * Another way to deal with this would be to ensure there is no horizontal shift, by measuring travel times at
     * exactly the right places instead of on a grid.
     */
    public double weightedSquaredError (Distribution other) {
        double sum = 0;
        // This is kind of ugly because we're only examining bins with nonzero probability (to avoid div by zero).
        // Observed data in a region with predicted zero probability should be an automatic fail for the model.
        for (int i = this.skip; i < this.fullWidth(); i++) {
            double pe = this.probabilityOf(i);
            double po = other.probabilityOf(i);
            Preconditions.checkState(pe >= 0); // Ensure non-negative for good measure.
            if (pe == 0) {
                System.out.println("Zero (expected probability; skipping.");
                continue;
            }
            // Errors are measured relative to the expected values, and stronger deviations emphasized by squaring.
            // Measuring relative to expected density compensates for the case where it is spread over a wider domain.
            sum += pow(po - pe, 2) / pe;
        }
        System.out.println("Squared error: " + sum);
        System.out.println("Root Squared error: " + sqrt(sum));
        return sum;
    }

    public void assertSimilar (Distribution observed) {
        double squaredError = this.weightedSquaredError(observed);
        showChartsIfEnabled(observed);
        assertTrue(squaredError < 0.025, String.format("Error metric too high at at %3f", squaredError));
    }

    public void showChartsIfEnabled (Distribution observed) {
        if (SHOW_CHARTS) {
            DistributionChart.showChart(this, observed);
        }
    }

    // This is ugly, it should be done some other way e.g. firstNonzero
    public int skip () {
        return skip;
    }

    /**
     * Remove any zeros from the beginning and end of the probability density array.
     * This improves both storage space and convolution efficiency.
     */
    public void trim () {
        int i = 0;
        while (i < masses.length && masses[i] == 0) {
            skip += 1;
            i++;
        }
        int firstNonzero = i;
        int lastNonzero = -1;
        while (i < masses.length) {
            if (masses[i] > 0) {
                lastNonzero = i;
            }
            i++;
        }
        masses = Arrays.copyOfRange(masses, firstNonzero, lastNonzero + 1);
    }

    /**
     * Here we are performing two related checks for a bit of redundancy and to check different parts of the system:
     * checking percentiles drawn from the observed distribution, as well as the full histogram of the distribution.
     * This double comparison could be done automatically with a method like Distribution.assertSimilar(TravelTimeResult).
     * @param destination the flattened 1D index into the pointset, which will be zero for single freeform points.
     */
    public void multiAssertSimilar(TravelTimeResult travelTimes, int destination) {
        // Check a goodness-of-fit metric on the observed distribution relative to this distribution.
        Distribution observed = Distribution.fromTravelTimeResult(travelTimes, destination);
        this.assertSimilar(observed);
        // Check that percentiles extracted from observed are similar to those predicted by this distribution.
        int[] travelTimePercentiles = travelTimes.getTarget(destination);
        DistributionTester.assertExpectedDistribution(this, travelTimePercentiles);
    }
}
