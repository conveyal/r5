package com.conveyal.r5.analyst.broker;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
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
import java.util.List;

/**
* A Grizzly Async Http Service (uses response suspend/resume)
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
        // Together with the HTTP method, path component 1 establishes which action the client wishes to take.
        if (pathComponents.length < 2) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setDetailMessage("path should have at least one part");
            TaskError error = new TaskError(null, "Path should have at least one part", "");
            OutputStream os = response.getOutputStream();
            mapper.writeValue(os, new TaskError[] { error });
            return;
        }

        String command = pathComponents[1];
        try {
            if (request.getMethod() == Method.HEAD) {
                /* Let the client know server is alive and URI + request are valid. */
                mapper.readTree(request.getInputStream());
                response.setStatus(HttpStatus.OK_200);
                return;
            }
            if (request.getMethod() == Method.GET && "jobs".equals(command)) {
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
            else if (request.getMethod() == Method.GET && "workers".equals(command)) {
                /* Report on all known workers. */
                response.setStatus(HttpStatus.OK_200);
                OutputStream os = response.getOutputStream();
                mapper.writeValue(os, broker.workerCatalog.observationsByWorkerId.values());
                os.close();
                return;
            }
            else if (request.getMethod() == Method.POST && "dequeue".equals(command)) {
                /*
                   Workers use this command to fetch tasks from a work queue.
                   They supply their R5 commit, network ID, and a unique worker ID to make sure they always get the
                   same category of work. Newer workers provide this information (and much more) in a JSON request body.
                   Older workers will include a simplified version of it in the URL and headers.
                   The method is POST because unlike GETs (which fetch status) it modifies the task queue on the server.
                */
                WorkerStatus workerStatus = JsonUtilities.objectFromRequestBody(request, WorkerStatus.class);
                String workType = pathComponents[2]; // Worker specifies single point or regional polling
                if (workerStatus == null) {
                    // Older worker did not supply a JSON body. Fill in the status from URL and headers.
                    workerStatus = new WorkerStatus();
                    workerStatus.networks = Sets.newHashSet(pathComponents[3]);
                    workerStatus.workerVersion = pathComponents[4];
                    workerStatus.workerId = request.getHeader(AnalystWorker.WORKER_ID_HEADER);
                }
                // Assume one loaded graph (or preferred graph at startup) in the current system
                // Add this worker to our catalog, tracking its graph affinity and the last time it was seen.
                broker.workerCatalog.catalog(workerStatus);
                WorkerCategory category = workerStatus.getWorkerCategory();
                if ("single".equals(workType)) {
                    // Worker is polling for single point tasks.
                    Broker.WrappedResponse wrappedResponse = new Broker.WrappedResponse(workerStatus.workerId, response);
                    request.getRequest().getConnection().addCloseListener(
                            (c, i) -> broker.removeSinglePointChannel(category, wrappedResponse));
                    // The request object will be shelved and survive after the handler function exits.
                    response.suspend();
                    broker.registerSinglePointChannel(category, wrappedResponse);
                }
                else if ("regional".equals(workType)) {
                    // Worker is polling for tasks from regional batch jobs.
                    request.getRequest().getConnection().addCloseListener(
                            (c, i) -> broker.removeSuspendedResponse(category, response));
                    // The request object will be shelved and survive after the handler function exits.
                    response.suspend();
                    broker.registerSuspendedResponse(category, response);
                }
                else {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    response.setDetailMessage("Context not found. Should be either 'single' or 'regional'.");
                }
            }
            else if (request.getMethod() == Method.POST && "enqueue".equals(command)) {
                /* The front end wants to enqueue work tasks. */
                String workType = pathComponents[2];
                if ("single".equals(workType)) {
                    // Enqueue a single priority task.
                    AnalysisTask task =
                            mapper.readValue(request.getInputStream(), AnalysisTask.class);
                    if (broker.enqueuePriorityTask(task, response)) {
                        // Enqueueing the priority task has set its internal taskId.
                        // TODO move all removal listener registration into the broker functions.
                        request.getRequest().getConnection()
                                .addCloseListener((closeable, iCloseType) -> broker.deletePriorityTask(task.taskId));
                        // The request object will be shelved and survive after the handler function exits.
                        response.suspend();
                    }
                    return;
                }
                else if ("regional".equals(workType)) {
                    // Enqueue a list of tasks that all belong to one job.
                    // A single task can represent all those tasks. The broker will expand it into a whole job.
                    RegionalTask templateTask =
                            mapper.readValue(request.getInputStream(), new TypeReference<RegionalTask>() { });
                    broker.enqueueTasksForRegionalJob(templateTask);
                    response.setStatus(HttpStatus.ACCEPTED_202);
                }
                else {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    response.setDetailMessage("Context not found; should be either 'single' or 'regional'");
                }
            }
            else if (request.getMethod() == Method.POST && "complete".equals(command)) {
                // Requests of the form: POST /complete/{success|<httpErrorStatusCode>}/<taskId>
                // Mark a specific high-priority task as completed, and record its result.
                // We were originally planning to do this with a DELETE request that has a body,
                // but that is nonstandard enough to anger many libraries including Grizzly.
                String successOrError = pathComponents[2];
                int taskId = Integer.parseInt(pathComponents[3]);
                Response suspendedProducerResponse = broker.deletePriorityTask(taskId);
                if (suspendedProducerResponse == null) {
                    // Signal the source of the response (the worker) that no one is waiting for that response anymore.
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                    return;
                }
                if ("success".equals(successOrError)) {
                    // The worker did not find any obvious problems with the task and is streaming back what it
                    // believes to be a reliable work result.
                    suspendedProducerResponse.setStatus(HttpStatus.OK_200);
                    suspendedProducerResponse.setContentType(request.getContentType());
                    String contentEncoding = request.getHeader("Content-Encoding");
                    if (contentEncoding != null) suspendedProducerResponse.setHeader("Content-Encoding", contentEncoding);
                } else {
                    // The worker is providing an error message because it spotted something wrong with the task.
                    suspendedProducerResponse.setStatus(Integer.parseInt(successOrError));
                    suspendedProducerResponse.setCharacterEncoding("utf-8");
                    suspendedProducerResponse.setContentType("application/json");
                }
                // Copy the result back to the connection that was the source of the task.
                try {
                    long length = ByteStreams.copy(request.getInputStream(), suspendedProducerResponse.getOutputStream());
                    LOG.info("Returning {} bytes to high-priority consumer for request {}", length, taskId);
                } catch (IOException | IllegalStateException ioex) {
                    // IOExceptions happens when the task producer did not wait to retrieve its result.
                    // Priority task result delivery is not guaranteed, so we don't need to retry.
                    // This is not an error from the worker's point of view.
                    // IllegalStateException can happen if we're in offline mode and we've already returned a 202.
                }
                // Prepare to pipe data back to the original source of the task (the UI) from the worker.
                // TODO clarify how it is that we were previously setting the status code of the suspended response after calling ByteStreams.copy. Is the body being buffered?
                suspendedProducerResponse.resume();
                // Tell source of the POSTed data (the worker) that the broker has handled it with no problems.
                response.setStatus(HttpStatus.OK_200);
                return;
            }
            else if (request.getMethod() == Method.DELETE) {
                // Used by workers to acknowledge completion of a batch task and remove it from queues,
                // avoiding re-delivery. Practically speaking this means the worker has put the result in a queue
                String context = pathComponents[1];
                String id = pathComponents[2];
                if ("tasks".equalsIgnoreCase(context)) {
                    int taskId = Integer.parseInt(id);
                    // This must not have been a priority task. Try to delete it as a normal job task.
                    if (broker.markTaskCompleted(taskId)) {
                        response.setStatus(HttpStatus.OK_200);
                    } else {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                    }
                } else if ("jobs".equals((context))) {
                    if (broker.deleteJob(id)) {
                        response.setStatus(HttpStatus.OK_200);
                        response.setDetailMessage("job deleted");
                    } else {
                        response.setStatus(HttpStatus.NOT_FOUND_404);
                        response.setDetailMessage("job not found");
                    }
                } else {
                    response.setStatus(HttpStatus.BAD_REQUEST_400);
                    response.setDetailMessage("Delete is only allowed for tasks and jobs.");
                    TaskError error = new TaskError(null, "Delete is only allowed for tasks and jobs", "");
                    OutputStream os = response.getOutputStream();
                    mapper.writeValue(os, new TaskError[] { error });
                    os.close();
                }
            } else {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.setDetailMessage("Unrecognized combination of HTTP method and command.");
                TaskError error = new TaskError(null, "Unrecognized combination of HTTP method and command.", "");
                OutputStream os = response.getOutputStream();
                mapper.writeValue(os, new TaskError[] { error });
                os.close();
            }
        } catch (JsonProcessingException jpex) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setDetailMessage("Could not decode/encode JSON payload. " + jpex.getMessage());
            TaskError error = new TaskError(null, "Could not decode/encode JSON payload. ", jpex.getMessage());
            OutputStream os = response.getOutputStream();
            mapper.writeValue(os, new TaskError[] { error });
            os.close();
            
            LOG.info("Error processing JSON from client", jpex);
        } catch (Exception ex) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.setDetailMessage(ex.toString());
            LOG.info("Error processing client request", ex);
        }
    }

}
