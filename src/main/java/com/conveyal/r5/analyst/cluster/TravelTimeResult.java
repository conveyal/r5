package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * Stores one or more percentiles of total travel time to each point in a pointset (aka targets or destinations).
 * This is the internal result produced by the worker for a single origin, nested inside a OneOriginResult instance.
 *
 * TODO standardize terminology: the terms targets, destinations, and points are used interchangeably throughout
 */
public class TravelTimeResult {

    public static final Logger LOG = LoggerFactory.getLogger(TravelTimeResult.class);

    /**
     * The number of travel times that will be recorded at each destination point.
     * This is currently equal to the number of percentiles being recorded.
     */
    public final int nSamplesPerPoint;

    public final int nPoints;

    /**
     * 2D array of travel times in minutes.
     * First index is percentile, second index is target number (grid cell or point number within a pointset).
     */
    int[][] values;

    /**
     * For each target, the number of times falling in each of 120 one-minute bins.
     * This is optional, generally used only for debugging and testing, so may be null if no histograms are recorded.
     * Since this is used for debugging and testing it might even be interesting to do it at one-second resolution or
     * just store every travel time, but binning makes it easy to compare with probability distributions.
     */
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
        if (task.recordTravelTimeHistograms) {
            // Initializing the array to a non-null value will enable histogram recording.
            LOG.warn("Recording travel time histograms at every desitination. " +
                    "This increases memory consumption and should only be enabled in tests.");
            histograms = new int[nPoints][120];
        }
    }

    public void setTarget(int targetIndex, int[] percentileTravelTimesMinutes) {
        if (percentileTravelTimesMinutes.length != nSamplesPerPoint) {
            throw new IllegalArgumentException("Incorrect number of values per pixel.");
        }
        for (int i = 0; i < percentileTravelTimesMinutes.length; i++) {
            values[i][targetIndex] = percentileTravelTimesMinutes[i];
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

    public void recordHistogramIfEnabled (int target, int[] travelTimesSeconds) {
        if (histograms != null) {
            int[] counts = histograms[target];
            for (int seconds : travelTimesSeconds) {
                if (seconds == UNREACHED) continue;
                int minutes = seconds / 60;
                counts[minutes] += 1;
            }
            // TODO scale by nIterations to get probabilities?
        }
    }

    /**
     * @return true if the search reached any destination cell, false if it did not reach any cells. No cells will be
     * reached when the origin point is outside the transport network. Some cells will still be reached via the street
     * network when we are outside the transit network but within the street network.
     */
    public boolean anyCellReached() {
        return Arrays.stream(values).anyMatch(vals -> Arrays.stream(vals).anyMatch(v -> v != UNREACHED));
    }

    /**
     * For the specified destination point index, return an array of 120 integers representing the number of paths with
     * each total travel time from zero to 120 minutes.
     */
    public int[] getHistogram (int pointIndex) {
        if (histograms == null) {
            throw new RuntimeException("Travel time histograms were not retained. Enable them in the request.");
        }
        return histograms[pointIndex];
    }

}
