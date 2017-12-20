package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.common.JsonUtilities;
import spark.Request;
import spark.Response;

/**
 * This class contains Spark HTTP request handler methods that are provided by the Analysis worker.
 * Currently this is only one method allowing the broker to push single point requests.
 * Created by abyrd on 2017-12-20
 */
public class AnalysisWorkerController {

    private final AnalystWorker analystWorker;

    public AnalysisWorkerController (AnalystWorker analystWorker) {
        this.analystWorker = analystWorker;
    }

    public Object handleSinglePoint (Request request, Response response) {
        TravelTimeSurfaceTask task = JsonUtilities.objectFromRequestBody(request, TravelTimeSurfaceTask.class);
        byte[] binaryResult = analystWorker.handleOneRequest(task);
        response.header("content-type", "application/octet-stream");
        // This will cause Spark Framework to gzip the data automatically if requested by the client
        response.header("Content-Encoding", "gzip");
        return binaryResult;
    }

}
