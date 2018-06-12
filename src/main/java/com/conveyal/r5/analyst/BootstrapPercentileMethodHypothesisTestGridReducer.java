package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Compute p-values that the two regional analysis results differ due to systematic variation (change in transit network,
 * percentile of interest, land use, time window, etc.) rather than due to random variation from the Monte Carlo search
 * process. This uses the confidence interval method of bootstrap hypothesis testing described on page 214 of Efron and
 * Tibshirani (1993). We perform a two-tailed test, so we are not making an assumptions about whether the change was positive
 * or negative; a significant value indicates that there was a change, and that the sign is correct.
 *
 * This technique works by constructing a confidence interval that has one end at 0 and the other end where it may lie to
 * make the confidence interval symmetric (i.e. the same amount of density in both tails). We use the percentile method
 * to calculate this confidence interval; while we are aware there are issues with this, the better bootstrap confidence
 * interval methods require us to store additional data so we can compute a jackknife estimate of acceleration (Efron
 * and Tibshirani 1993, ch. 14).
 *
 * While it might initially seem that we could use the permutation test described earlier in that chapter, we in fact cannot.
 * It attractively promises to compare two distributions, which is exactly what we have, but it promises to compare some
 * statistic of those distributions. We're not interested in the difference in means of the sampling distributions, we're
 * interested in the the difference of the full sampling distribution. To use the permutation test, we would have to apply
 * it one level up the stack, when computing the regional results, and compute two regional runs simultaneously so we could
 * permute them when we still had the travel times for all iterations in memory.
 *
 * References
 * Efron, B., & Tibshirani, R. J. (1993). An Introduction to the Bootstrap. Boca Raton, FL: Chapman and Hall/CRC.
 *
 * This class is not referenced within the R5 library, but is used by the Analysis frontend.
 */
public class BootstrapPercentileMethodHypothesisTestGridReducer {
    private static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    /**
     * Calculate the probability at each origin that a random individual sample from regional analysis B is larger than one from regional
     * analysis A. We do this empirically and exhaustively by for each origin looping over every possible combination of
     * samples and taking a difference, then evaluating the number that yielded results greater than zero.
     *
     * The regional analysis access grids must be of identical size and zoom level, and a Grid object (the same as is used
     * for destination grids) will be returned, with probabilities scaled from 0 to 100,000.
     *
     * This function is called from the Analysis front end, not called within R5 library.
     */
    public Grid computeImprovementProbability (String resultBucket, String regionalAnalysisAKey, String regionalAnalysisBKey) throws IOException {
        S3Object aGrid = s3.getObject(resultBucket, regionalAnalysisAKey);
        S3Object bGrid = s3.getObject(resultBucket, regionalAnalysisBKey);
        return computeImprovementProbability(aGrid.getObjectContent(), bGrid.getObjectContent());
    }

    public Grid computeImprovementProbability(InputStream a, InputStream b) throws IOException {
        LittleEndianDataInputStream aIn = new LittleEndianDataInputStream(new GZIPInputStream(a));
        LittleEndianDataInputStream bIn = new LittleEndianDataInputStream(new GZIPInputStream(b));

        validateHeaderAndVersion(aIn);
        validateHeaderAndVersion(bIn);

        int aZoom = aIn.readInt();
        int aWest = aIn.readInt();
        int aNorth = aIn.readInt();
        int aWidth = aIn.readInt();
        int aHeight = aIn.readInt();

        int bZoom = bIn.readInt();
        int bWest = bIn.readInt();
        int bNorth = bIn.readInt();
        int bWidth = bIn.readInt();
        int bHeight = bIn.readInt();

        if (aZoom != bZoom ||
                aWest != bWest ||
                aNorth != bNorth ||
                aWidth != bWidth ||
                aHeight != bHeight) {
            throw new IllegalArgumentException("Grid sizes for comparison must be identical!");
        }

        // number of iterations need not be equal, the computed probability is still valid even if they are not
        // as the probability of choosing any particular sample is still uniform within each scenario.
        int aIterations = aIn.readInt();
        int bIterations = bIn.readInt();

        Grid out = new Grid(aZoom, aWidth, aHeight, aNorth, aWest);

        // pixels are in row-major order, iterate over y on outside
        for (int y = 0; y < aHeight; y++) {
            for (int x = 0; x < aWidth; x++) {
                int[] aValues = new int[aIterations];
                int[] bValues = new int[bIterations];

                for (int iteration = 0, val = 0; iteration < aIterations; iteration++) {
                    aValues[iteration] = (val += aIn.readInt());
                }

                for (int iteration = 0, val = 0; iteration < bIterations; iteration++) {
                    bValues[iteration] = (val += bIn.readInt());
                }

                out.grid[x][y] = computeValuesForOrigin(aValues, bValues);
            }
        }

        return out;
    }

    private static void validateHeaderAndVersion(LittleEndianDataInputStream input) throws IOException {
        char[] header = new char[8];
        for (int i = 0; i < 8; i++) {
            header[i] = (char) input.readByte();
        }

        if (!"ACCESSGR".equals(new String(header))) {
            throw new IllegalArgumentException("Input not in access grid format!");
        }

        int version = input.readInt();

        if (version != ACCESS_GRID_VERSION) {
            throw new IllegalArgumentException(String.format("Version mismatch of access grids, expected %s, found %s", ACCESS_GRID_VERSION, version));
        }
    }

    /**
     * Given the origin coordinates and the values from the two grids, compute a value for the output grid.
     */
    protected double computeValuesForOrigin(int[] aValues, int[] bValues) {
        // compute the value
        int nBelowZero = 0;
        int nZero = 0;
        int nAboveZero = 0;
        int nTotal = 0;

        // get the point estimate of the difference
        int pointEstimate = bValues[0] - aValues[0];
        if (pointEstimate == 0) return 0; // no difference, not statistically significant

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

        // if the point estimate was less than zero, assume the confidence interval is less than zero
        // This could be wrong if the accessibility does not lie on the same side of zero as the majority of the density.
        // TODO Efron and Tibshirani don't really discuss how to handle that case, and in particular don't discuss two-
        // tailed tests at all.
        if (pointEstimate < 0) {
            // compute the density that lies at or above zero. We have changed the technique slightly from what is
            // described in Efron and Tibshirani 1993 to explicitly handle values that are exactly 0 (important because
            // we are working with discretized, integer data).
            double densityAtOrAboveZero = (double) (nZero + nAboveZero) / nTotal;
            // two tailed test, take this much density off the other end as well, and that's the p-value for this test.
            pVal = 2 * densityAtOrAboveZero;
        } else {
            // compute the density that lies at or below zero
            double densityAtOrBelowZero = (double) (nBelowZero + nZero) / nTotal;
            // two tailed test
            pVal = 2 * densityAtOrBelowZero;
        }

        // fix up case where point est does not lie on same side of 0 as majority of density.
        if (pVal > 1) pVal = 1;

        // scale probability to 0 - 100,000
        // note that the return value can actually be less than 0 if a majority of the density lies on the
        // opposite side of zero from the point estimate. Maybe we shouldn't be looking at the point estimate
        // NB for historical reasons, the probability grids show the probability of change, not the probability of no
        // change (i.e. the p-value). Invert the values (i.e. replace p with alpha)
        return (1 - pVal) * 1e5;
    }

}
