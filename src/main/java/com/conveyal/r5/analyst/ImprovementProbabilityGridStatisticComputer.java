package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Using two access grids for different scenarios (each containing N accessibility indicator values at each cell resulting from
 * Monte Carlo draws and different departure minutes), compute the probability that, given the two scenarios, the scenario
 * used to create grid B will produce a higher accessibility value than the scenario used to compute grid A. Since this
 * uses instantaneous accessibility (based on the accessibility at a single minute and Monte Carlo draw), it is in the same
 * family as the AndrewOwenMeanGridStatisticComputer, and has the same fungibility concerns documented there.
 *
 * Note that this does not extend GridStatisticComputer as it works with two access grids simultaneously.
 */
public class ImprovementProbabilityGridStatisticComputer extends DualGridStatisticComputer {
    @Override
    protected double computeValuesForOrigin(int x, int y, int[] aValues, int[] bValues) {
        int positive = 0;
        // loop over all scenario and base values for this cell and determine how many have positive difference
        // this is effectively a convolution, we are calculating the difference (a special case of the sum) for
        // two empirical distributions. At some point in the future we could save the probability distribution of
        // changes rather than the probability of improvement.
        for (int scenarioVal : bValues) {
            for (int baseVal : aValues) {
                if (scenarioVal > baseVal) positive++;
            }
        }

        // Store as probabilities on 0 to 100,000 scale, and store in the grid.
        // We are using the same classes used for opportunity grids here, so they are stored as doubles, and
        // will be cast to ints when saved. This is to avoid rounding error when projecting opportunities into
        // the grids, which we're not doing here, but these grids are small enough the use of doubles instead of ints
        // is relatively harmless.
        return (double) positive / (aValues.length * bValues.length) * 100_000;
    }
}
