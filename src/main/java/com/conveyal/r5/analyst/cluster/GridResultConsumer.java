package com.conveyal.r5.analyst.cluster;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * Listens to an SQS queue and manages grid results coming back; this runs in an independent thread for the lifetime
 * of the listening process.
 */
public class GridResultConsumer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(GridResultConsumer.class);
    private static final AmazonSQS sqs = new AmazonSQSClient();

    public final String sqsUrl;
    public final String outputBucket;

    public Map<String, GridResultAssembler> assemblers = new HashMap<>();

    public GridResultConsumer (String sqsUrl, String outputBucket) {
        this.sqsUrl = sqsUrl;
        this.outputBucket = outputBucket;
    }

    public void run () {
        while (true) {
            // TODO if no jobs are registered, don't waste money talking to SQS

            try {
                ReceiveMessageRequest req = new ReceiveMessageRequest(sqsUrl);
                req.setWaitTimeSeconds(20);
                req.setMaxNumberOfMessages(10);
                req.setMessageAttributeNames(Collections.singleton("jobId"));
                ReceiveMessageResult res = sqs.receiveMessage(req);

                // parallelStream? ExecutorService?
                List<DeleteMessageBatchRequestEntry> deleteRequests = res.getMessages().stream()
                        .map(m -> {
                            MessageAttributeValue jobIdAttr = m.getMessageAttributes().get("jobId");
                            String jobId = jobIdAttr != null ? jobIdAttr.getStringValue() : null;

                            if (jobId == null) {
                                LOG.error("Message does not have a Job ID, silently discarding");
                                return new DeleteMessageBatchRequestEntry(m.getMessageId(), m.getReceiptHandle());
                            }

                            if (!assemblers.containsKey(jobId)) {
                                // TODO is this the right thing to do?
                                LOG.warn("Received message for invalid job ID {}, returning to queue", jobId);
                                return null;
                            }

                            assemblers.get(jobId).handleMessage(m);

                            return new DeleteMessageBatchRequestEntry(m.getMessageId(), m.getReceiptHandle());
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (!deleteRequests.isEmpty()) {
                    sqs.deleteMessageBatch(sqsUrl, deleteRequests);
                }
            } catch (Exception e) {
                // TODO figure out if exception is permanent
                LOG.info("Error connecting to regional result queue. Assuming this is transient network issues. Retrying in 60s");
                try {
                    Thread.sleep(3600);
                } catch (InterruptedException ie) {
                    LOG.info("Interrupted, shutting down monitor thread");
                    return;
                }
            }
        }
    }

    public void registerJob (GridRequest request) {
        this.assemblers.put(request.jobId, new GridResultAssembler(request, outputBucket));
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
