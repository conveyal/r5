package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * Compute a particular percentile of a given regional analysis and return the results as a grid.
 */
public class PercentileGridSampler {
    private static final AmazonS3 s3 = new AmazonS3Client();

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    /** Compute a particular percentile of a grid (percentile between 0 and 100) */
    public static Grid computePercentile (String resultsBucket, String key, double percentile) throws IOException {
        percentile /= 100;

        if (percentile < 0 || percentile > 1) {
            throw new IllegalArgumentException("Invalid percentile!");
        }

        S3Object accessGrid = s3.getObject(resultsBucket, key);

        LittleEndianDataInputStream input = new LittleEndianDataInputStream(new GZIPInputStream(accessGrid.getObjectContent()));

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

        int zoom = input.readInt();
        int west = input.readInt();
        int north = input.readInt();
        int width = input.readInt();
        int height = input.readInt();
        int nIterations = input.readInt();

        Grid grid = new Grid(zoom, width, height, north, west);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] values = new int[nIterations];

                for (int iteration = 0, val = 0; iteration < nIterations; iteration++) {
                    values[iteration] = (val += input.readInt());
                }

                // compute percentiles
                Arrays.sort(values);
                double index = percentile * (values.length - 1);
                int lowerIndex = (int) Math.floor(index);
                int upperIndex = (int) Math.ceil(index);
                double remainder = index % 1;
                // weight the upper and lower values by where the percentile falls between them (linear interpolation)
                double val = values[upperIndex] * remainder + values[lowerIndex] * (1 - remainder);
                grid.grid[x][y] = val;
            }
        }

        input.close();

        return grid;
    }
}
