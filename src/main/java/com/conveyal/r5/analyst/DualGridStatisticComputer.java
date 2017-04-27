package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * A DualGridStatisticComputer computes statistics based on two grids.
 */
public abstract class DualGridStatisticComputer {
    private static final AmazonS3 s3 = new AmazonS3Client();
    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    /**
     * Calculate the probability at each origin that a random individual sample from regional analysis B is larger than one from regional
     * analysis A. We do this empirically and exhaustively by for each origin looping over every possible combination of
     * samples and taking a difference, then evaluating the number that yielded results greater than zero.
     *
     * The regional analysis access grids must be of identical size and zoom level, and a Grid object (the same as is used
     * for destination grids) will be returned, with probabilities scaled from 0 to 100,000.
     */
    public Grid computeImprovementProbability (String resultBucket, String regionalAnalysisAKey, String regionalAnalysisBKey) throws IOException {
        S3Object aGrid = s3.getObject(resultBucket, regionalAnalysisAKey);
        S3Object bGrid = s3.getObject(resultBucket, regionalAnalysisBKey);
        LittleEndianDataInputStream aIn = new LittleEndianDataInputStream(new GZIPInputStream(aGrid.getObjectContent()));
        LittleEndianDataInputStream bIn = new LittleEndianDataInputStream(new GZIPInputStream(bGrid.getObjectContent()));
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

                out.grid[x][y] = computeValuesForOrigin(x, y, aValues, bValues);
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

    /** Given the origin coordinates and the values from the two grids, compute a value for the output grid */
    protected abstract double computeValuesForOrigin (int x, int y, int[] aValues, int[] bValues);
}
