package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.common.JsonUtilities;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

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
        // This header will cause the Spark Framework to gzip the data automatically if requested by the client.
        // FIXME I'm not seeing this on the wire, is the client asking for gzipped responses?
        response.header("Content-Encoding", "gzip");
        TravelTimeSurfaceTask task = JsonUtilities.objectFromRequestBody(request, TravelTimeSurfaceTask.class);
        // TODO do not return raw binary data from method, return better typed response.
        // TODO possibly move data preloading to this point, to allow returning different HTTP status codes.
        try {
            byte[] binaryResult = analystWorker.handleOneSinglePointTask(task);
            response.status(HttpStatus.OK_200);
            response.header("Content-Type", "application/octet-stream");
            return binaryResult;
        } catch (WorkerNotReadyException workerNotReadyException) {
            // We're using exceptions for flow control here, which is kind of ugly. Define a ResultOrError<T> class?
            response.status(HttpStatus.ACCEPTED_202);
            response.header("Content-Type", "text/plain");
            return workerNotReadyException.getMessage();
        } catch (Exception exception) {
            response.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.header("Content-Type", "text/plain");
            return exception.toString();
        }
    }

}
