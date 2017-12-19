package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Listens to an SQS queue and manages grid results coming back; this runs in an independent thread for the lifetime
 * of the listening process. This is only used by the frontend repo - not used within R5 itself.
 * This manages multiple grid result assemblers running for multiple regional analysis jobs simultaneously. It
 * splits a stream of messages so each message goes into the right result file.
 *
 * TODO merge into RegionalAnalysisManager
 */
public class GridResultQueueConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(GridResultQueueConsumer.class);

    public final String sqsUrl;
    public final String outputBucket;

    public Map<String, GridResultAssembler> assemblers = new HashMap<>();

    public GridResultQueueConsumer(String sqsUrl, String outputBucket) {
        this.sqsUrl = sqsUrl;
        this.outputBucket = outputBucket;
    }

    /**
     * The taskId seems to come from the binary result itself.
     */
    public void registerResult (String jobId, byte[] result) {
        if (assemblers.containsKey(jobId)) {
            assemblers.get(jobId).handleMessage(result);
        } else {
            LOG.error("Received message for invalid job ID {}, silently discarding.", jobId);
        }
    }

    /** Register a grid assembler for a particular task */
    public void registerJob (AnalysisTask request, GridResultAssembler assembler) {
        this.assemblers.put(request.jobId, assembler);
    }

    public void deleteJob(String jobId) {
        GridResultAssembler assembler = this.assemblers.remove(jobId);
        try {
            assembler.terminate();
        } catch (Exception e) {
            LOG.error("Could not terminate grid result assembler, this may waste disk space", e);
        }
    }
}
