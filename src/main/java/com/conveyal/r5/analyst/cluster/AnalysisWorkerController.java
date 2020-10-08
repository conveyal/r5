package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.error.ScenarioApplicationException;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.util.ExceptionUtils;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.analyst.cluster.AnalysisWorker.addJsonToGrid;

/**
 * This class contains Spark HTTP request handler methods that are served up by Analysis workers.
 * Currently the worker exposes only a single method that allows the broker to push it single point requests for
 * immediate processing.
 */
public class AnalysisWorkerController {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisWorkerController.class);

    private final AnalysisWorker analysisWorker;

    public AnalysisWorkerController (AnalysisWorker analysisWorker) {
        this.analysisWorker = analysisWorker;
    }

    public Object handleSinglePoint (Request request, Response response) throws IOException {
        // This header will cause the Spark Framework to gzip the data automatically if requested by the client.
        // FIXME I'm not seeing this on the wire, is the client asking for gzipped responses?
        response.header("Content-Encoding", "gzip");
        TravelTimeSurfaceTask task = JsonUtilities.objectFromRequestBody(request, TravelTimeSurfaceTask.class);
        // TODO do not return raw binary data from method, return better typed response.
        // TODO possibly move data preloading to this point, to allow returning different HTTP status codes.

        if (task.logRequest){
            LOG.info(request.body());
        }

        try {
            try {
                byte[] binaryResult = analysisWorker.handleOneSinglePointTask(task);
                response.status(HttpStatus.OK_200);
                if (task.getFormat().equals(TravelTimeSurfaceTask.Format.GEOTIFF)) {
                    response.header("Content-Type", "application/x-geotiff");
                } else {
                    response.header("Content-Type", "application/octet-stream");
                }
                return binaryResult;
            } catch (WorkerNotReadyException workerNotReadyException) {
                // We're using exceptions for flow control here, which is kind of ugly. Define a ResultOrError<T> class?
                if (workerNotReadyException.isError()) {
                    if (workerNotReadyException.asyncLoaderState.exception instanceof ScenarioApplicationException) {
                        return reportTaskErrors(response,
                                ((ScenarioApplicationException)workerNotReadyException.asyncLoaderState.exception).taskErrors);
                    } else {
                        return jsonResponse(response, HttpStatus.BAD_REQUEST_400,
                                ExceptionUtils.asString(workerNotReadyException.asyncLoaderState.exception));
                    }
                } else {
                    return jsonResponse(response, HttpStatus.ACCEPTED_202, workerNotReadyException.asyncLoaderState.message);
                }
            }
        } catch (Exception exception) {
            // Handle any uncaught exceptions in any of the above code.
            // TODO shouldn't some of these serious uncaught errors be 500s?
            return jsonResponse(response, HttpStatus.BAD_REQUEST_400, ExceptionUtils.asString(exception));
        }
    }

    private static byte[] jsonResponse (Response response, int httpStatusCode, String message) {
        response.status(httpStatusCode);
        response.header("Content-Type", "application/json");
        Map<String, String> jsonObject = new HashMap<>();
        jsonObject.put("message", message);
        return JsonUtilities.objectToJsonBytes(jsonObject);
    }

    /**
     * Report that the task could not be processed due to errors.
     * Reuses the code that appends warnings as JSON to a grid, but without the grid.
     */
    public static byte[] reportTaskErrors(Response response, List<TaskError> taskErrors) throws IOException {
        response.status(HttpStatus.BAD_REQUEST_400);
        response.header("Content-Type", "application/json");
        // TODO expand task errors, this just logs the memory address of the list.
        LOG.warn("Reporting errors in response to single-point request:\n" + taskErrors.toString());
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        addJsonToGrid(byteArrayOutputStream, null, taskErrors, Collections.emptyList());
        byteArrayOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

}
