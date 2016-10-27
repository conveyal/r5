package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Sample from an access grid of instantaneous accessibility to compute the probability of improvement.
 * Since this uses instantaneous accessibility, it is in the same family of methods as the AndrewOwenMeanGridSampler;
 * see notes there about fungibility concerns.
 */
public class ImprovementProbabilityGridSampler {
    private static final AmazonS3 s3 = new AmazonS3Client();

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    /**
     * Calculate the probability at each origin that a random sample from regional analysis B is larger than one from regional
     * analysis A. We do this empirically and exhaustively by for each origin looping over every possible combination of
     * samples and taking a difference, then evaluating the number that yielded results greater than zero.
     *
     * Returns a grid with probabilities scaled from 0 to 100,000.
     */
    public static Grid computeImprovementProbability (String resultBucket, String baseKey, String scenarioKey) throws IOException {
        S3Object baseGrid = s3.getObject(resultBucket, baseKey);
        S3Object scenarioGrid = s3.getObject(resultBucket, scenarioKey);
        LittleEndianDataInputStream base = new LittleEndianDataInputStream(new GZIPInputStream(baseGrid.getObjectContent()));
        LittleEndianDataInputStream scenario = new LittleEndianDataInputStream(new GZIPInputStream(scenarioGrid.getObjectContent()));
        validateHeaderAndVersion(base);
        validateHeaderAndVersion(scenario);

        int zoom = base.readInt();
        int west = base.readInt();
        int north = base.readInt();
        int width = base.readInt();
        int height = base.readInt();

        int scenarioZoom = scenario.readInt();
        int scenarioWest = scenario.readInt();
        int scenarioNorth = scenario.readInt();
        int scenarioWidth = scenario.readInt();
        int scenarioHeight = scenario.readInt();

        if (zoom != scenarioZoom ||
                west != scenarioWest ||
                north != scenarioNorth ||
                width != scenarioWidth ||
                height != scenarioHeight) {
            throw new IllegalArgumentException("Grid sizes for comparison must be identical!");
        }

        // number of iterations need not be equal
        int baseIterations = base.readInt();
        int scenarioIterations = scenario.readInt();

        Grid out = new Grid(zoom, width, height, north, west);

        // pixels are in row-major order, iterate over y on outside
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] baseValues = new int[baseIterations];
                int[] scenarioValues = new int[scenarioIterations];
                int positive = 0;

                for (int iteration = 0, val = 0; iteration < baseIterations; iteration++) {
                    baseValues[iteration] = (val += base.readInt());
                }

                for (int iteration = 0, val = 0; iteration < scenarioIterations; iteration++) {
                    scenarioValues[iteration] = (val += scenario.readInt());
                }

                // convolve
                for (int scenarioVal : scenarioValues) {
                    for (int baseVal : baseValues) {
                        if (scenarioVal > baseVal) positive++;
                    }
                }

                out.grid[x][y] = (double) positive / (scenarioIterations * baseIterations) * 100_000;
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
}
