package com.conveyal.r5;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.AccessibilityResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
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

    public final AnalysisTask task; // The task for which this instance holds the results TODO eliminate field

    public final TimeGrid timeGrid;

    public final AccessibilityResult accessibility;

    public OneOriginResult(AnalysisTask task, TimeGrid timeGrid, AccessibilityResult accessibility) {
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

    // Convert the accessibility results for this origin into a RegionalWorkResult.
    // This is a stopgap adapter that should eventually be eliminated. There's no real reason for two different
    // accessibility classes to exist. AccessibilityResult and RegionalWorkResult should probably be merged into one
    // class, they were just created in parallel to serve essentially the same purpose.
    // Or, maybe we should just nest an accessibilityResult inside the RegionalWorkResult, which just adds task ID info.
    public RegionalWorkResult toRegionalWorkResult(AnalysisTask task) {
        RegionalWorkResult result = new RegionalWorkResult(task.jobId, task.taskId,
                accessibility.grids.length, accessibility.percentiles.length, accessibility.cutoffs.length);
        result.setAcccessibilityValue(0, 0, 0, (int) accessibility.getAccessibility(0, 0, 0));
        return result;
    }

}
