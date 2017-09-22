package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.scenario.GridStatisticComputer;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.primitives.Ints;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * Take an access grid, which is a grid containing a vector of accessibility at each origin, and collapse to grid of
 * scalars containing a percentile of accessibility for each origin.
 */
public class PercentileGridStatisticComputer extends GridStatisticComputer {
    public final double percentile;

    public PercentileGridStatisticComputer (double percentile) {
        this.percentile = percentile;
    }

    @Override
    protected double computeValueForOrigin(int x, int y, int[] valuesThisOrigin) {
        // compute percentiles
        Arrays.sort(valuesThisOrigin);
        double index = percentile / 100 * (valuesThisOrigin.length - 1);
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);
        double remainder = index % 1;
        // weight the upper and lower values by where the percentile falls between them (linear interpolation)
        return valuesThisOrigin[upperIndex] * remainder + valuesThisOrigin[lowerIndex] * (1 - remainder);
    }
}
