package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.scenario.GridStatisticComputer;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Access grids are three-dimensional arrays, with the first two dimensions consisting of x and y coordinates of origins
 * within the regional analysis, and the third dimension reflects multiple values of the indicator of interest. This could
 * be instantaneous accessibility results for each Monte Carlo draw when computing average instantaneous accessibility (i.e.
 * Owen-style accessibility), or it could be multiple bootstrap replications of the sampling distribution of accessibility
 * given median travel time (see Conway, M. W., Byrd, A. and van Eggermond, M. "A Statistical Approach to Comparing
 * Accessibility Results: Including Uncertainty in Public Transport Sketch Planning," paper presented at the 2017 World
 * Symposium of Transport and Land Use Research, Brisbane, QLD, Australia, Jul 3-6.)
 *
 * An ExtractingGridStatisticComputer simply grabs the value at a particular index within each origin.
 * When storing bootstrap replications of travel time, we also store the point estimate (using all Monte Carlo draws
 * equally weighted) as the first value, so an ExtractingGridStatisticComputer(0) can be used to retrieve the point estimate.
 */
public class ExtractingGridStatisticComputer extends GridStatisticComputer {
    public final int index;

    /** Initialize with the index to extract */
    public ExtractingGridStatisticComputer (int index) {
        this.index = index;
    }

    @Override
    protected double computeValueForOrigin(int x, int y, int[] valuesThisOrigin) {
        return valuesThisOrigin[index];
    }
}
