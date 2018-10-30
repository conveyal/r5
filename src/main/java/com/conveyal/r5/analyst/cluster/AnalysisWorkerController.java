package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.common.JsonUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.ByteArrayOutputStream;

/**
 * This class contains Spark HTTP request handler methods that are served up by Analysis workers.
 * Currently the broker exposes only a single method that allows the broker to push it single point requests for
 * immediate processing.
 */
public class AnalysisWorkerController {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorkerController.class);

    private final AnalystWorker analystWorker;

    public AnalysisWorkerController (AnalystWorker analystWorker) {
        this.analystWorker = analystWorker;
    }

    public Object handleSinglePoint (Request request, Response response) {
        // Record the fact that this worker is busy so it will not shut down
        analystWorker.lastSinglePointTime = System.currentTimeMillis();
        TravelTimeSurfaceTask task = JsonUtilities.objectFromRequestBody(request, TravelTimeSurfaceTask.class);
        // TODO do not return raw binary data from method, return better typed response
        // Move data preloading to this point.
        byte[] binaryResult = analystWorker.handleOneSinglePointTask(task);
        response.header("content-type", "application/octet-stream");
        // This will cause Spark Framework to gzip the data automatically if requested by the client
        response.header("Content-Encoding", "gzip");
        return binaryResult;
    }

}
