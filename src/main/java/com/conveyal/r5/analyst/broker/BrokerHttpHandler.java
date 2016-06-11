package com.conveyal.r5.analyst.broker;

import com.conveyal.r5.analyst.cluster.GenericClusterRequest;
import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
* A Grizzly Async Http Service (uses reponse suspend/resume)
 * https://blogs.oracle.com/oleksiys/entry/grizzly_2_0_httpserver_api1
 *
 * When resuming a response object, "The only reliable way to check the socket status is to try to read or
 * write something." Though you also have:
 *
 * response.getRequest().getRequest().getConnection().isOpen()
 * response.getRequest().getRequest().getConnection().addCloseListener();
 * But none of these work, I've tried all three of them. You can even write to the outputstream after the connection
 * is closed.
 * Solution: networkListener.getTransport().setIOStrategy(SameThreadIOStrategy.getInstance());
 * This makes all three work! isOpen, CloseListener, and IOExceptions from flush();
 *
 * Grizzly has Comet support, but this seems geared toward subscriptions to broadcast events.
 *
*/
class BrokerHttpHandler extends HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerHttpHandler.class);

    private ObjectMapper mapper = JsonUtilities.objectMapper;

    private Broker broker;

    public BrokerHttpHandler(Broker broker) {
        this.broker = broker;
    }

    @Override
    public void service(Request request, Response response) throws Exception {

        response.setContentType("application/json");

        // request.getRequestURI(); // without protocol or server, only request path
        // request.getPathInfo(); // without handler base path
        String[] pathComponents = request.getPathInfo().split("/");
        // Path component 0 is empty since the path always starts with a slash.
        if (pathComponents.length < 2) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setDetailMessage("path should have at least one part");
        }

        try {
            if (request.getMethod() == Method.HEAD) {
                /* Let the client know server is alive and URI + request are valid. */
                mapper.readTree(request.getInputStream());
                response.setStatus(HttpStatus.OK_200);
                return;
            }
            else if (request.getMethod() == Method.GET && "jobs".equals(pathComponents[1])) {
                /* Fetch status of all jobs. */
                List<JobStatus> ret = new ArrayList<>();
                broker.jobs.forEach(job -> ret.add(new JobStatus(job)));
                // Add a summary of all jobs to the list.
                ret.add(new JobStatus(ret));
                response.setStatus(HttpStatus.OK_200);
                OutputStream os = response.getOutputStream();
                mapper.writeValue(os, ret);
                os.close();
                return;
            }
            else if (request.getMethod() == Method.GET && "workers".equals(pathComponents[1])) {
                /* Report on all known workers. */
                response.setStatus(HttpStatus.OK_200);
                OutputStream os = response.getOutputStream();
                mapper.writeValue(os, broker.workerCatalog.observationsByWorkerId.values());
                os.close();
                return;
            }
            else if (request.getMethod() == Method.POST) {
                /* dequeue messages. */
                String command = pathComponents[1];

                if ("dequeue".equals(command)) {
                    String graphAffinity = pathComponents[2];
                    request.getRequest().getConnection()
                            .addCloseListener((closeable, iCloseType) -> {
                                broker.removeSuspendedResponse(graphAffinity, response);
                            });
                    response.suspend(); // The request should survive after the handler function exits.
                    broker.registerSuspendedResponse(graphAffinity, response);
                }

                /* not dequeueing, enqueuing */
                else if ("enqueue".equals(command)) {
                    String context = pathComponents[2];
                    if ("priority".equals(context)) {
                        // Enqueue a single priority task
                        GenericClusterRequest task = mapper.readValue(request.getInputStream(),
                                GenericClusterRequest.class);
                        broker.enqueuePriorityTask(task, response);
                        // Enqueueing the priority task has set its internal taskId.
                        // TODO move all removal listener registration into the broker functions.
                        request.getRequest().getConnection()
                                .addCloseListener((closeable, iCloseType) -> {
                                    broker.deletePriorityTask(task.taskId);
                                });
                        response.suspend(); // The request should survive after the handler function exits.
                        return;

                    } else if ("jobs".equals(context)) {
                        // Enqueue a list of tasks that belong to jobs
                        List<GenericClusterRequest> tasks = mapper
                                .readValue(request.getInputStream(),
                                        new TypeReference<List<GenericClusterRequest>>() {
                                        });
                        // Pre-validate tasks checking that they are all on the same job
                        GenericClusterRequest exemplar = tasks.get(0);
                        for (GenericClusterRequest task : tasks) {
                            if (task.jobId != exemplar.jobId || task.graphId != exemplar.graphId) {
                                response.setStatus(HttpStatus.BAD_REQUEST_400);
                                response.setDetailMessage(
                                        "All tasks must be for the same graph and job.");
                            }
                        }
                        broker.enqueueTasks(tasks);
                        response.setStatus(HttpStatus.ACCEPTED_202);
                    } else {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                        response.setDetailMessage(
                                "Context not found; should be either 'jobs' or 'priority'");
                    }
                }
                else if ("complete".equals(command)) {
                    // Mark a specific high-priority task as completed, and record its result.
                    // We were originally planning to do this with a DELETE request that has a body,
                    // but that is nonstandard enough to anger many libraries including Grizzly.
                    int taskId = Integer.parseInt(pathComponents[3]);
                    Response suspendedProducerResponse = broker.deletePriorityTask(taskId);
                    if (suspendedProducerResponse == null) {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                        return;
                    }
                    // Copy the result back to the connection that was the source of the task.
                    try {
                        ByteStreams.copy(request.getInputStream(),
                                suspendedProducerResponse.getOutputStream());
                    } catch (IOException ioex) {
                        // Apparently the task producer did not wait to retrieve its result. Priority task result delivery
                        // is not guaranteed, we don't need to retry, this is not considered an error by the worker.
                    }
                    response.setStatus(HttpStatus.OK_200);
                    suspendedProducerResponse.setStatus(HttpStatus.OK_200);
                    suspendedProducerResponse.resume();
                    return;
                }
                else if ("single".equals(command)) {
                    // await single point responses
                    String graphAffinity = pathComponents[2];
                    Broker.WrappedResponse wr = new Broker.WrappedResponse(request, response);
                    request.getRequest().getConnection().addCloseListener((c, i) -> {
                        broker.removeSinglePointChannel(graphAffinity, wr);
                    });
                    response.suspend();
                    broker.registerSinglePointChannel(graphAffinity, wr);
                }
            } else if (request.getMethod() == Method.DELETE) {
                /* Acknowledge completion of a task and remove it from queues, avoiding re-delivery. */
                if ("tasks".equalsIgnoreCase(pathComponents[1])) {
                    int taskId = Integer.parseInt(pathComponents[2]);
                    // This must not have been a priority task. Try to delete it as a normal job task.
                    if (broker.markTaskCompleted(taskId)) {
                        response.setStatus(HttpStatus.OK_200);
                    } else {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                    }
                } else if ("jobs".equals((pathComponents[1]))) {
                    if (broker.deleteJob(pathComponents[2])) {
                        response.setStatus(HttpStatus.OK_200);
                        response.setDetailMessage("job deleted");
                    }
                    else {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                        response.setDetailMessage("job not found");
                    }
                } else {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    response.setDetailMessage("Delete is only allowed for tasks and jobs.");
                }
            } else {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.setDetailMessage("Unrecognized HTTP method.");
            }
        } catch (JsonProcessingException jpex) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setDetailMessage("Could not decode/encode JSON payload. " + jpex.getMessage());
            LOG.info("Error processing JSON from client", jpex);
        } catch (Exception ex) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.setDetailMessage(ex.toString());
            LOG.info("Error processing client request", ex);
        }
    }

    public void writeJson (Response response, Object object) throws IOException {
        mapper.writeValue(response.getOutputStream(), object);
    }

}
