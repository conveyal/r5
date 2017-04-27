package com.conveyal.r5.analyst;

/**
 * Compute p-values that the two regional analysis results differ due to systematic variation (change in transit network,
 * percentile of interest, land use, time window, etc.) rather than due to random variation from the Monte Carlo search
 * process. This uses the confidence interval method of bootstrap hypothesis testing described on page 214 of Efron and
 * Tibshirani (1993). We perform a two-tailed test, so we are not making an assumptions about whether the change was positive
 * or negative.
 *
 * This technique works by constructing a confidence interval that has one end at 0 and the other end where it may lie to
 * make the confidence interval symmetric (i.e. the same amount of density in both tails). We use the percentile method
 * to calculate this confidence interval; while we are aware there are issues with this, the better bootstrap confidence
 * interval methods require us to store additional data so we can compute a jackknife estimate of acceleration (Efron
 * and Tishirani 1993, ch. 14).
 *
 * TODO reread this section of Efron and Tibshirana (1993) to be sure I understand it when I have the book handy.
 *
 * References
 * Efron, B., & Tibshirani, R. J. (1993). An Introduction to the Bootstrap. Boca Raton, FL: Chapman and Hall/CRC.
 */
public class BootstrapPercentileHypothesisTestGridStatisticComputer extends DualGridStatisticComputer {
    @Override
    protected double computeValuesForOrigin(int x, int y, int[] aValues, int[] bValues) {
        // compute the value
        int nBelowZero = 0;
        int nZero = 0;
        int nAboveZero = 0;
        int nTotal = 0;

        // get the point estimate of the difference
        int pointEstimate = bValues[0] - aValues[0];
        if (pointEstimate == 0) return 1e5; // no difference, not statistically significant

        // subtract every value in b from every value in a
        // this creates a bootstrapped sampling distribution of the differences, since each bootstrap sample in each analysis
        // is independent of all others (we've taken a lot of care to ensure this is the case).
        for (int aIdx = 1; aIdx < aValues.length; aIdx++) {
            // TODO a and b values are used more than once. This doesn't create bootstrap dependence, correct?
            int aVal = aValues[aIdx];
            for (int bIdx = 1; bIdx < bValues.length; bIdx++, nTotal++) {
                int bVal = bValues[bIdx];
                int difference = bVal - aVal;

                if (difference > 0) nAboveZero++;
                else if (difference < 0) nBelowZero++;
                else nZero++;
            }
        }

        double pVal;
        // TODO should we be looking at the point estimate here at all?
        if (pointEstimate < 0) {
            // compute the density that lies at or above zero
            double densityAtOrAboveZero = (double) (nZero + nAboveZero) / nTotal;
            // two tailed test, take this much density off the other end as well, and that's the alpha for this test.
            pVal = 2 * densityAtOrAboveZero;
        } else {
            // compute the density that lies at or below zero
            double densityAtOrBelowZero = (double) (nBelowZero + nZero) / nTotal;
            // two tailed test
            pVal = 2 * densityAtOrBelowZero;
        }

        // scale probability to 0 - 100,000
        // note that the return value can actually be greater than 100,000 if a majority of the density lies on the
        // opposite side of zero from the point estimate. Maybe we shouldn't be looking at the point estimate
        return pVal * 1e5;
    }
}
