package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.FastRaptorWorker;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

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

    // For each target, the number of times falling in each of 120 one-minute bins. This is optional and may be null.
    int[][] histograms;

    public TravelTimeResult(AnalysisWorkerTask task) {
        nPoints = task.nTargetsPerOrigin();
        nSamplesPerPoint = task.percentiles.length;
        // Initialization: Fill the values array the default unreachable value.
        // This way the grid is valid even if we don't write anything into it
        // (rather than saying everything is reachable in zero minutes).
        values = new int[nSamplesPerPoint][nPoints];
        for (int i = 0; i < nSamplesPerPoint; i++) {
            for (int j = 0; j < nPoints; j++) {
                values[i][j] = UNREACHED;
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

    /**
     * @return an array of all percentiles of travel time for the specified target.
     */
    public int[] getTarget (int targetIndex) {
        int[] ret = new int[values.length];
        for (int p = 0; p < ret.length; p++) {
            ret[p] = values[p][targetIndex];
        }
        return ret;
    }

    public int[][] getValues() { return values;}

    public void recordHistogram (int target, int[] travelTimesSeconds) {
        // Lazy-initialize only when histograms are being recorded
        if (histograms == null) {
            histograms = new int[nPoints][120];
        }
        int[] counts = histograms[target];
        for (int seconds : travelTimesSeconds) {
            if (seconds == UNREACHED) continue;
            int minutes = seconds / 60;
            counts[minutes] += 1;
        }
        // TODO scale by nIterations to get probabilities?
    }

    /**
     * @return true if the search reached any destination cell, false if it did not reach any cells. No cells will be
     * reached when the origin point is outside the transport network. Some cells will still be reached via the street
     * network when we are outside the transit network but within the street network.
     */
    public boolean anyCellReached() {
        return Arrays.stream(values).anyMatch(vals -> Arrays.stream(vals).anyMatch(v -> v != UNREACHED));
    }

    public int[] getHistogram (int pointIndex) {
        return histograms[pointIndex];
    }

}
