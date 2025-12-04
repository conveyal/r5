package com.conveyal.analysis;

import com.conveyal.r5.analyst.Grid;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 */
public class SelectingGridReducer {

    /** Version of the access grid format we read */
    private static final int ACCESS_GRID_VERSION = 0;

    private final int index;

    private final ByteBuffer swapBuffer = ByteBuffer.allocate(8);

    /** Initialize with the index to extract */
    public SelectingGridReducer(int index) {
        this.index = index;
    }

    public Grid compute (InputStream rawInput) {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(rawInput))) {
            // Ideally, this access grid reading logic should not be embedded in this reduce operation.
            char[] header = new char[8];
            for (int i = 0; i < 8; i++) {
                header[i] = (char) input.readByte();
            }
            if (!"ACCESSGR".equals(new String(header))) {
                throw new IllegalArgumentException("Input not in access grid format!");
            }
            int version = readIntSwap(input);
            if (version != ACCESS_GRID_VERSION) {
                throw new IllegalArgumentException(String.format("Version mismatch of access grids, expected %s, found %s", ACCESS_GRID_VERSION, version));
            }
            int zoom = readIntSwap(input);
            int west = readIntSwap(input);
            int north = readIntSwap(input);
            int width = readIntSwap(input);
            int height = readIntSwap(input);

            // The number of samples stored at each origin; these could be instantaneous accessibility values for each
            // Monte Carlo draw, or they could be bootstrap replications of a sampling distribution of accessibility given
            // median travel time.
            int nSamples = readIntSwap(input);
            Grid outputGrid = new Grid(west, north, width, height, zoom);
            int[] valuesThisOrigin = new int[nSamples];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // input values are delta-coded per origin, so use val to keep track of current value
                    for (int iteration = 0, val = 0; iteration < nSamples; iteration++) {
                        val += readIntSwap(input);
                        valuesThisOrigin[iteration] = val;
                    }
                    // compute percentiles
                    outputGrid.grid[x][y] = valuesThisOrigin[index];
                }
            }
            input.close();
            return outputGrid;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// NOT THREAD SAFE, reuses per-instance byte buffer
    private int swap (int x) {
        swapBuffer.order(ByteOrder.nativeOrder()).putInt(0, x);
        return swapBuffer.order(ByteOrder.BIG_ENDIAN).getInt(0);
    }

    private int readIntSwap (DataInputStream dis) throws IOException {
        int swapped = dis.readInt();
        return swap(swapped);
    }
}
