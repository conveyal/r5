package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.FastRaptorWorker;

import java.util.Arrays;

/**
 * Stores various samples of travel time (usually reduced to selected percentiles of total travel time) to every
 * point in a pointset (aka targets)
 */

public class TravelTimeResult {

    // In the past, when doing large numbers of bootstrapping samples, this used to be a long
    // but now it should never be so huge and should fit in an int without fear of overflow.
    public final int nSamplesPerPoint;

    public final int nPoints;

    // Travel time values, indexed by percentile (sample) and target (grid cell/point)
    int[][] values;

    public TravelTimeResult(AnalysisWorkerTask task) {
        nPoints = task.nTargetsPerOrigin();
        nSamplesPerPoint = task.percentiles.length;
        // Initialization: Fill the values array the default unreachable value.
        // This way the grid is valid even if we don't write anything into it
        // (rather than saying everything is reachable in zero minutes).
        values = new int[nSamplesPerPoint][nPoints];
        for (int i = 0; i < nSamplesPerPoint; i++) {
            for (int j = 0; j < nPoints; j++) {
                values[i][j] = FastRaptorWorker.UNREACHED;
            }
        }
    }

    // At 2 million destinations and 100 int values per destination (every percentile) we still only are at 800MB.
    // So no real risk of overflowing an int index.
    public void setTarget(int targetIndex, int[] targetValues) {
        if (targetValues.length != nSamplesPerPoint) {
            throw new IllegalArgumentException("Incorrect number of values per pixel.");
        }
        for (int i = 0; i < targetValues.length; i++) {
            values[i][targetIndex] = targetValues[i];
        }
    }

    public int[][] getValues() { return values;}

    /**
     * @return true if the search reached any destination cell, false if it did not reach any cells. No cells will be
     * reached when the origin point is outside the transport network. Some cells will still be reached via the street
     * network when we are outside the transit network but within the street network.
     */
    public boolean anyCellReached() {
        return Arrays.stream(values).anyMatch(vals -> Arrays.stream(vals).anyMatch(v -> v != FastRaptorWorker.UNREACHED));
    }

}
