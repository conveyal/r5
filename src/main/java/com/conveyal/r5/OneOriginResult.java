package com.conveyal.r5;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.multipoint.MultipointDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This provides a single return type for all the kinds of results we can get from a travel time computer and reducer
 * for a single origin point:
 * Travel times to all destination cells, accessibility indicator values for various travel time cutoffs and percentiles
 * of travel time, travel time breakdowns into wait and ride and walk time, and paths to all destinations.
 * Created by abyrd on 2018-01-10
 */
public class OneOriginResult {

    private static final Logger LOG = LoggerFactory.getLogger(OneOriginResult.class);

    /** SQS client and Base64 encoding: to be removed with new broker. */
    private static final AmazonSQS sqs = new AmazonSQSClient();
    private static final Base64.Encoder base64 = Base64.getEncoder();

    public final AnalysisTask task; // The task for which this instance holds the results TODO eliminate field

    public final TimeGrid timeGrid;

    public final Origin accessibility;

    public OneOriginResult(AnalysisTask task, TimeGrid timeGrid, Origin accessibility) {
        this.task = task;
        this.timeGrid = timeGrid;
        this.accessibility = accessibility;
    }

    /**
     * TODO pull this whole thing out to the caller so we don't need to store a ref to the task in its own result
     * @param scenarioApplicationWarnings from the TransportNetwork
     */
    public void writeTravelTimes(OutputStream outputStream, List<TaskError> scenarioApplicationWarnings) {
        try {
            LOG.info("Travel time surface of size {} kB complete", (timeGrid.nValues * 4 + timeGrid.HEADER_SIZE) / 1000);

            // if the outputStream was null in the constructor, write to S3.
            // TODO make this outputstream in the caller
            if (outputStream == null) {
                outputStream = MultipointDataStore.getOutputStream(task, task.taskId + "_times.dat", "application/octet-stream");
            }

            // TODO just make two different methods for writing different fields or just call the methods on this object's fields from the caller
            if (task instanceof TravelTimeSurfaceTask) {
                // This travel time surface is being produced by a single-origin task.
                // We could be making a grid or a TIFF.
                TravelTimeSurfaceTask timeSurfaceTask = (TravelTimeSurfaceTask) task;
                if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GRID) {
                    timeGrid.writeGrid(outputStream);
                } else if (timeSurfaceTask.getFormat() == TravelTimeSurfaceTask.Format.GEOTIFF) {
                    timeGrid.writeGeotiff(outputStream);
                }
            } else {
                // This travel time surface is being produced by a regional task. We must be making a static site.
                // Write the grid format.
                timeGrid.writeGrid(outputStream);
            }

            LOG.info("Travel time surface written, appending metadata with {} warnings", scenarioApplicationWarnings.size());

            // Append scenario application warning JSON to result
            // TODO do this with Map<String, Object> not a class definition.
            Map<String, List<TaskError>> errorsToSerialize = new HashMap<>();
            errorsToSerialize.put("scenarioApplicationWarnings", scenarioApplicationWarnings);
            JsonUtilities.objectMapper.writeValue(outputStream, errorsToSerialize);
            LOG.info("Done writing");
            outputStream.close();
        } catch (IOException e) {
            LOG.warn("Unexpected IOException returning travel time surface to client", e);
        }
    }


    // Will fail if task is not a RegionalTask
    public void accessibilityToSqs() {
        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            accessibility.write(baos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultQueueConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(((RegionalTask)task).outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(task.jobId));
        sqs.sendMessage(smr);
    }
}
