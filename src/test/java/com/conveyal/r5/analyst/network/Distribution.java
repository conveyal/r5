package com.conveyal.r5.analyst.network;

import java.util.Arrays;

/**
 * Used to model expected travel times in testing, for comparison against actual times reported by the routing system.
 * Designed to facilitate convolution to find the distribution of a sum of random variables,
 * modeling successive waits for pure frequency routes or waits for scheduled routes given a departure time distribution.
 */
public class Distribution {

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
        final int width = 40;
        double max = Arrays.stream(masses).max().getAsDouble();
        double scale = width / max;
        for (int i = 0; i < skip; i++) {
            System.out.printf("%2d\n", i);
        }
        for (int i = 0; i < masses.length; i++) {
            System.out.printf("%2d %s\n", i + skip, "=".repeat((int)(masses[i] * scale) + 1));
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

}
