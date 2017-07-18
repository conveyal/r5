package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AccessGridWriter;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
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
 * is not practical), and summarize that list to a few percentiles of travel time and return them in an AccessGrid-format
 * file (see AccessGridWriter for format documentation).
 */
public class TravelTimeSurfaceReducer implements PerTargetPropagater.TravelTimeReducer {
    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeSurfaceReducer.class);

    /** The results encoded as an access grid */
    private AccessGridWriter encodedResults;

    /** The output stream to write the result to */
    private OutputStream outputStream;

    /** The network used to compute the travel time results */
    public final TransportNetwork network;

    /** The task used to create travel times being reduced herein */
    public final TravelTimeSurfaceTask task;

    public TravelTimeSurfaceReducer (TravelTimeSurfaceTask task, TransportNetwork network, OutputStream outputStream) {
        this.task = task;
        this.network = network;
        this.outputStream = outputStream;

        try {
            // use an in-memory access grid, don't specify disk cache file
            encodedResults = new AccessGridWriter(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
        } catch (IOException e) {
            // in memory, should not be able to throw this
            throw new RuntimeException(e);
        }
    }

    @Override
    public void accept (int target, int[] times) {
        int nPercentiles = task.percentiles.length;
        // sort the times at each target and read off percentiles
        Arrays.sort(times);
        int[] results = new int[nPercentiles];

        for (int i = 0; i < nPercentiles; i++) {
            int offset = (int) Math.round(task.percentiles[i] / 100d * times.length);
            // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
            // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
            // which is what we want. But maybe this is tying the backend and frontend too closely.
            results[i] = times[offset] == FastRaptorWorker.UNREACHED ? FastRaptorWorker.UNREACHED : times[offset] / 60;
        }

        int x = target % task.width;
        int y = target / task.width;
        try {
            encodedResults.writePixel(x, y, results);
        } catch (IOException e) {
            // can't happen as we're not using a file system backed output
            throw new RuntimeException(e);
        }
    }

    @Override
    public void finish () {
        try {
            LOG.info("Travel time surface of size {}kb complete", encodedResults.getBytes().length / 1000);
            outputStream.write(encodedResults.getBytes());

            LOG.info("Travel time surface written, appending metadata with {} warnings", network.scenarioApplicationWarnings.size());

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


    private static class ResultMetadata {
        public Collection<TaskError> scenarioApplicationWarnings;
    }
}
