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
 */
public class ImprovementProbabilityGridStatisticComputer {
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
    public static Grid computeImprovementProbability (String resultBucket, String regionalAnalysisAKey, String regionalAnalysisBKey) throws IOException {
        S3Object baseGrid = s3.getObject(resultBucket, regionalAnalysisAKey);
        S3Object scenarioGrid = s3.getObject(resultBucket, regionalAnalysisBKey);
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

        // number of iterations need not be equal, the computed probability is still valid even if they are not
        // as the probability of choosing any particular sample is still uniform within each scenario.
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

                // loop over all scenarion and base values for this cell and determine how many have positive difference
                // this is effectively a convolution, we are calculating the difference (a special case of the sum) for
                // two empirical distributions. At some point in the future we could save the probability distribution of
                // changes rather than the probability of improvement.
                for (int scenarioVal : scenarioValues) {
                    for (int baseVal : baseValues) {
                        if (scenarioVal > baseVal) positive++;
                    }
                }

                // Store as probabilities on 0 to 100,000 scale, and store in the grid.
                // We are using the same classes used for opportunity grids here, so they are stored as doubles, and
                // will be cast to ints when saved. This is to avoid rounding error when projecting opportunities into
                // the grids, which we're not doing here, but these grids are small enough the use of doubles instead of ints
                // is relatively harmless.
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
