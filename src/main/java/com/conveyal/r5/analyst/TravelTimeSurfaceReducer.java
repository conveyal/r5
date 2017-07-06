package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AccessGridWriter;
import com.conveyal.r5.analyst.cluster.AnalysisRequest;
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
 * From travel times to targets for each iteration, produce a binary travel time surface with percentiles of travel time.
 */
public class TravelTimeSurfaceReducer implements PerTargetPropagater.TravelTimeReducer {
    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeSurfaceReducer.class);

    /** The results encoded as an access grid */
    private AccessGridWriter encodedResults;

    /** The output stream to write the result to */
    private OutputStream outputStream;

    /** The network used to compute the travel time results */
    public final TransportNetwork network;

    /** The request used to create travel times being reduced herein */
    public final AnalysisRequest request;

    public TravelTimeSurfaceReducer (AnalysisRequest request, TransportNetwork network, OutputStream outputStream) {
        this.request = request;
        this.network = network;
        this.outputStream = outputStream;

        try {
            encodedResults = new AccessGridWriter(request.zoom, request.west, request.north, request.width, request.height, request.percentiles.length);
        } catch (IOException e) {
            // in memory, should not be able to throw this
            throw new RuntimeException(e);
        }
    }

    @Override
    public void accept (int target, int[] times) {
        int nPercentiles = request.percentiles.length;
        // sort the times at each target and read off percentiles
        Arrays.sort(times);
        int[] results = new int[nPercentiles];

        for (int i = 0; i < nPercentiles; i++) {
            int offset = (int) Math.round(request.percentiles[i] / 100d * times.length);
            // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
            // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
            // which is what we want. But maybe this is tying the backend and frontend too closely.
            results[i] = times[offset] == FastRaptorWorker.UNREACHED ? FastRaptorWorker.UNREACHED : times[offset] / 60;
        }

        int x = target % request.width;
        int y = target / request.width;
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
