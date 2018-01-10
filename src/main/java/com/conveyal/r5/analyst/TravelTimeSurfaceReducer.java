package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;

import java.io.IOException;
import java.util.Arrays;

/**
 * Take the travel times to targets at each iteration (passed in one target at a time, because storing them all in memory
 * is not practical), and summarize that list to a few percentiles of travel time and return them in an AccessGrid-format
 * file (see AccessGridWriter for format documentation).
 *
 * FIXME the destinations are always passed in in order. Why not just stream them through?
 * i.e. call startWrite() then writeOnePixel() in a loop, then endWrite()
 */
public class TravelTimeSurfaceReducer implements PerTargetPropagater.TravelTimeReducer {

    /** Travel time results encoded as an access grid */
    private TimeGrid timeGrid;

    /** The task used to create travel times being reduced herein */
    public final AnalysisTask task;

    public TravelTimeSurfaceReducer (AnalysisTask task) {
        this.task = task;
        timeGrid = new TimeGrid(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
    }

    @Override
    public void recordTravelTimesForTarget (int target, int[] times) {
        int nPercentiles = task.percentiles.length;
        // Sort the times at each target and read off percentiles
        Arrays.sort(times);
        int[] results = new int[nPercentiles];

        for (int i = 0; i < nPercentiles; i++) {
            // We scale the interval between the beginning and end elements of the array (the min and max values).
            // In an array with N values the interval is N-1 elements. We should be scaling N-1, which makes the result
            // always defined even when using a high percentile and low number of elements.  Previously, this caused
            // an error below when requesting the 95th percentile when times.length = 1 (or any length less than 10).
            int offset = (int) Math.round(task.percentiles[i] / 100d * (times.length-1));
            // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
            // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
            // which is what we want. But maybe converting to minutes before we actually export a binary format is tying
            // the backend and frontend (which makes use of UInt8 typed arrays) too closely.
            results[i] = times[offset] == FastRaptorWorker.UNREACHED ? FastRaptorWorker.UNREACHED : times[offset] / 60;
        }

        int x = target % task.width;
        int y = target / task.width;
        try {
            // TODO Add timeGrid.writePixel(n, results);
            timeGrid.writePixel(x, y, results);
        } catch (IOException e) {
            // can't happen as we're not using a file system backed output
            throw new RuntimeException(e);
        }
    }

    /**
     * Write the accumulated results out to the location specified in the request, or the output stream.
     *
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * AccessGridWriter (encodedResults) will have a buffer full of UNREACHED. This allows shortcutting around
     * routing and propagation when the origin point is not connected to the street network.
     */
    @Override
    public OneOriginResult finish () {
        return new OneOriginResult(task, timeGrid, null);
    }

}
