package com.conveyal.analysis;

import com.conveyal.r5.analyst.Grid;
import com.google.common.io.LittleEndianDataInputStream;

import java.io.IOException;
import java.io.InputStream;
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
 * A SelectingGridReducer simply grabs the value at a particular index within each origin.
 * When storing bootstrap replications of travel time, we also store the point estimate (using all Monte Carlo draws
 * equally weighted) as the first value, so a SelectingGridReducer(0) can be used to retrieve the point estimate.
 *
 * This class is not referenced within R5, but is used by the Analysis front end.
 */
public class SelectingGridReducer {

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    public final int index;

    /** Initialize with the index to extract */
    public SelectingGridReducer(int index) {
        this.index = index;
    }

    public Grid compute (InputStream rawInput) throws IOException {
        LittleEndianDataInputStream input = new LittleEndianDataInputStream(new GZIPInputStream(rawInput));

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

        // The number of samples stored at each origin; these could be instantaneous accessibility values for each
        // Monte Carlo draw, or they could be bootstrap replications of a sampling distribution of accessibility given
        // median travel time.
        int nSamples = input.readInt();

        Grid outputGrid = new Grid(zoom, width, height, north, west);

        int[] valuesThisOrigin = new int[nSamples];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // input values are delta-coded per origin, so use val to keep track of current value
                for (int iteration = 0, val = 0; iteration < nSamples; iteration++) {
                    val += input.readInt();
                    valuesThisOrigin[iteration] = val;
                }
                // compute percentiles
                outputGrid.grid[x][y] = valuesThisOrigin[index];
            }
        }
        input.close();
        return outputGrid;
    }

}
