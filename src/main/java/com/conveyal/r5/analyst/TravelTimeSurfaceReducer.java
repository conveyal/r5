package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.multipoint.MultipointDataStore;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;

/**
 * Take the travel times to targets at each iteration (passed in one target at a time, because storing them all in memory
 * is not practical), and summarize that list to a few percentiles of travel time.
 * If routing to a grid, return them in an AccessGrid-format file (see AccessGridWriter for format
 * documentation).
 * If routing to individual points, return them.
 *
 * FIXME the destinations are always passed in in order. Why not just stream them through?
 * i.e. call startWrite() then writeOnePixel() in a loop, then endWrite()
 */
public class TravelTimeSurfaceReducer implements PerTargetPropagater.TravelTimeReducer {
    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeSurfaceReducer.class);

    /** Travel time results encoded as an access grid */
    private TimeGrid timeGrid;

    /** The output stream to write the result to */
    private OutputStream outputStream;

    /** The network used to compute the travel time results */
    public final TransportNetwork network;

    /** The task used to create travel times being reduced herein */
    public final AnalysisTask task;

    public TravelTimeSurfaceReducer(AnalysisTask task, TransportNetwork network, OutputStream outputStream) {
        this.task = task;
        this.network = network;
        this.outputStream = outputStream;

        try {
            // use an in-memory access grid, don't specify disk cache file
            timeGrid = new TimeGrid(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
            timeGrid.initialize("ACCESSGR", 0);

        } catch (IOException e) {
            // in memory, should not be able to throw this
            throw new RuntimeException(e);
        }
    }

    @Override
    public void recordTravelTimesForTarget(int target, int[] times) {
        int nPercentiles = task.percentiles.length;
        // sort the times at each target and read off percentiles
        Arrays.sort(times);
        int[] results = new int[nPercentiles];

        for (int i = 0; i < nPercentiles; i++) {
            // We scale the interval between the beginning and end elements of the array (the min and max values).
            // In an array with N values the interval is N-1 elements. We should be scaling N-1, which makes the result
            // always defined even when using a high percentile and low number of elements.  Previously, this caused
            // an error below when requesting the 95th percentile when times.length = 1 (or any length less than 10).
            int offset = (int) Math.round(task.percentiles[i] / 100d * (times.length - 1));
            // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
            // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
            // which is what we want. But maybe converting to minutes before we actually export a binary format is tying
            // the backend and frontend (which makes use of UInt8 typed arrays) too closely.
            results[i] = times[offset] == FastRaptorWorker.UNREACHED ? FastRaptorWorker.UNREACHED : times[offset] / 60;
        }

        int x = target % task.width;
        int y = target / task.width;
        try {
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
    public void finish() {
        if (this.outputStream != null) {
            try {
                LOG.info("Travel time surface of size {} kB complete", (timeGrid.nValues * 4 + timeGrid.HEADER_SIZE) / 1000);

                // if the outputStream was null in the constructor, write to S3.
                if (outputStream == null) {
                    outputStream = MultipointDataStore.getOutputStream(task, task.taskId + "_times.dat", "application/octet-stream");
                }

                TravelTimeSurfaceTask timeSurfaceTask = (TravelTimeSurfaceTask) task;

                if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GRID) {
                    timeGrid.writeGrid(outputStream);
                } else if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GEOTIFF) {
                    timeGrid.writeGeotiff(outputStream);
                }

                LOG.info("Travel time surface written, appending metadata with {} warnings",
                        network.scenarioApplicationWarnings.size());

                // Append scenario application warning JSON to result
                ResultMetadata metadata = new ResultMetadata();
                metadata.scenarioApplicationWarnings = network.scenarioApplicationWarnings;
                JsonUtilities.objectMapper.writeValue(outputStream, metadata);

                LOG.info("Done writing");

                outputStream.close();
            } catch (IOException e) {
                LOG.warn("Unexpected IOException returning travel time surface to client", e);
            }
        }
    }

    private static class ResultMetadata {
        public Collection<TaskError> scenarioApplicationWarnings;
    }
}
