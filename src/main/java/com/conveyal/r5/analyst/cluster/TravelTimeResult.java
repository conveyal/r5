package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.profile.FastRaptorWorker;

import java.io.DataOutput;
import java.util.Arrays;

/**
 * Stores various samples of travel time (usually reduced to selected percentiles of total travel time) to every
 * point in a pointset (aka targets)
 */

public class TravelTimeResult {

    // used to be stored as longs, but can probably still use with impunity without fear of overflow
    public final int nSamplesPerPoint;

    public final int nPoints;

    // Travel time values, indexed by percentile (sample) and target (grid cell/point)
    int[][] values;

    public TravelTimeResult(AnalysisTask task) {
        nSamplesPerPoint = task.percentiles.length;

        if (task instanceof RegionalTask && ((RegionalTask) task).nTravelTimeTargetsPerOrigin != 0) {
            // Number of destination targets specified explicitly in task
            nPoints = ((RegionalTask) task).nTravelTimeTargetsPerOrigin;
        } else {
            // Number of destination targets not specified explicitly, so infer from grid
            nPoints = task.width * task.height;
        }

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

    public TravelTimeResult(PointSet pointSet, int[] times) {
        nPoints = pointSet.featureCount();
        nSamplesPerPoint = 1;
        values = new int[0][times.length];
        values[0] = times;
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

    public int[] getTravelTimesForSample(int sampleIndex) { return values[sampleIndex]; }

    public int getTravelTimeToPoint(int sampleIndex, int pointIndex){
        return values[sampleIndex][pointIndex];
    }

    /**
     * @return true if the search reached any destination cell, false if it did not reach any cells. No cells will be
     * reached when the origin point is outside the transport network. Some cells will still be reached via the street
     * network when we are outside the transit network but within the street network.
     */
    public boolean anyCellReached() {
        return Arrays.stream(values).anyMatch(vals -> Arrays.stream(vals).anyMatch(v -> v != FastRaptorWorker.UNREACHED));
    }

    public void writeToDataOutput(DataOutput dataOutput) {

    }

    /**
     * Write the grid out to a persistence buffer, an abstraction that will perform compression and allow us to save
     * it to a local or remote storage location.
     */
    public PersistenceBuffer writeToPersistenceBuffer() {
        PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
        this.writeToDataOutput(persistenceBuffer.getDataOutput());
        persistenceBuffer.doneWriting();
        return persistenceBuffer;
    }

}
