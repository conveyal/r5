package com.conveyal.r5.analyst.network;

import com.conveyal.r5.analyst.cluster.TravelTimeResult;

import java.util.Arrays;

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
     * Find the probability mass of the overlapping region of the two distributions. The amount of "misplaced"
     * probability is one minus overlap. Overlap is slightly more straightforward to calculate directly than mismatch.
     */
    public double overlap (Distribution other) {
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

    public void assertSimilar (Distribution observed) {
        if (SHOW_CHARTS) {
            DistributionChart.showChart(this, observed);
        }
        double overlapPercent = this.overlap(observed) * 100;
        assertTrue(overlapPercent >= 95, String.format("Overlap less than 95%% at %3f", overlapPercent));
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
}
