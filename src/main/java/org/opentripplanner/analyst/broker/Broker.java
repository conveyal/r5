package org.opentripplanner.analyst.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.opentripplanner.analyst.cluster.AnalystClusterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * This class watches for incoming requests for work tasks, and attempts to match them to enqueued tasks.
 * It draws tasks fairly from all users, and fairly from all jobs within each user, while attempting to respect the
 * cache affinity of each worker (give it tasks on the same graph it has been working on recently).
 *
 * When no work is available, the polling functions return immediately. Workers are expected to sleep and re-poll
 * after a few tens of seconds.
 *
 * TODO if there is a backlog of work (the usual case when jobs are lined up) workers will constantly change graphs
 * We need a queue of deferred work: (job, timestamp) when a job would have fairly had its work consumed  if a worker was available.
 * Anything that survives at the head of that queue for more than e.g. one minute gets forced on a non-affinity worker.
 * Any new workers without an affinity preferentially pull work off the deferred queue.
 * Polling worker connections scan the deferred queue before ever going to the main circular queue.
 * When the deferred queue exceeds a certain size, that's when we must start more workers.
 *
 * We should distinguish between two cases:
 * 1. we were waiting for work and woke up because work became available.
 * 2. we were waiting for a consumer and woke up when one arrived.
 *
 * The first case implies that many workers should migrate toward the new work.
 *
 * Two key ideas are:
 * 1. Least recently serviced queue of jobs
 * 2. Affinity Homeostasis
 *
 * If we can constantly keep track of the ideal proportion of workers by graph (based on active queues),
 * and the true proportion of consumers by graph (based on incoming requests) then we can decide when a worker's graph
 * affinity should be ignored.
 *
 * It may also be helpful to mark jobs every time they are skipped in the LRU queue. Each time a job is serviced,
 * it is taken out of the queue and put at its end. Jobs that have not been serviced float to the top.
 */
public class Broker implements Runnable {

    // TODO catalog of recently seen consumers by affinity with IP: response.getRequest().getRemoteAddr();

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    public final CircularList<Job> jobs = new CircularList<>();

    private int nUndeliveredTasks = 0;

    private int nWaitingConsumers = 0; // including some that might be closed

    private int nextTaskId = 0;

    private ObjectMapper mapper = new ObjectMapper();

    /** The messages that have already been delivered to a worker. */
    TIntObjectMap<AnalystClusterRequest> deliveredTasks = new TIntObjectHashMap<>();

    /** The time at which each task was delivered to a worker, to allow re-delivery. */
    TIntIntMap deliveryTimes = new TIntIntHashMap();

    /** Requests that are not part of a job and can "cut in line" in front of jobs for immediate execution. */
    private Queue<AnalystClusterRequest> priorityTasks = new ArrayDeque<>();

    /** Priority requests that have already been farmed out to workers, and are awaiting a response. */
    private TIntObjectMap<Response> priorityResponses = new TIntObjectHashMap<>();

    /** Outstanding requests from workers for tasks, grouped by worker graph affinity. */
    Map<String, Deque<Response>> consumersByGraph = new HashMap<>();

    // Queue of tasks to complete Delete, Enqueue etc. to avoid synchronizing all the functions ?

    /**
     * Enqueue a task for execution ASAP, planning to return the response over the same HTTP connection.
     * Low-reliability, no re-delivery.
     */
    public synchronized void enqueuePriorityTask (AnalystClusterRequest task, Response response) {
        task.taskId = nextTaskId++;
        priorityTasks.add(task);
        priorityResponses.put(task.taskId, response);
    }

    /** Enqueue some tasks for queued execution possibly much later. Results will be saved to S3. */
    public synchronized void enqueueTasks (List<AnalystClusterRequest> tasks) {
        Job job = findJob(tasks.get(0)); // creates one if it doesn't exist
        for (AnalystClusterRequest task : tasks) {
            task.taskId = nextTaskId++;
            job.addTask(task);
            nUndeliveredTasks += 1;
            LOG.debug("Enqueued task id {} in job {}", task.taskId, job.jobId);
            if (task.graphId != job.graphId) {
                LOG.warn("Task graph ID {} does not match job graph ID {}.", task.graphId, job.graphId);
            }
        }
        // Wake up the delivery thread if it's waiting on input.
        // This wakes whatever thread called wait() while holding the monitor for this Broker object.
        notify();
    }

    /** Long poll operations are enqueued here. */
    public synchronized void registerSuspendedResponse(String graphId, Response response) {
        // The workers are not allowed to request a specific job or task, just a specific graph and queue type.
        Deque<Response> deque = consumersByGraph.get(graphId);
        if (deque == null) {
            deque = new ArrayDeque<>();
            consumersByGraph.put(graphId, deque);
        }
        deque.addLast(response);
        nWaitingConsumers += 1;
        // Wake up the delivery thread if it's waiting on consumers.
        // This is whatever thread called wait() while holding the monitor for this QBroker object.
        notify();
    }

    /** When we notice that a long poll connection has closed, we remove it here. */
    public synchronized boolean removeSuspendedResponse(String graphId, Response response) {
        Deque<Response> deque = consumersByGraph.get(graphId);
        if (deque == null) {
            return false;
        }
        if (deque.remove(response)) {
            nWaitingConsumers -= 1;
            LOG.debug("Removed closed connection from queue.");
            logQueueStatus();
            return true;
        }
        return false;
    }

    private void logQueueStatus() {
        LOG.info("{} priority, {} undelivered, {} consumers waiting.", priorityTasks.size(), nUndeliveredTasks, nWaitingConsumers);
    }

    /**
     * Pull the next job queue with undelivered work fairly from users and jobs.
     * Pass some of that work to a worker, blocking if necessary until there are workers available.
     */
    public synchronized void deliverTasksForOneJob () throws InterruptedException {

        // Wait until there are some undelivered tasks.
        while (nUndeliveredTasks == 0) {
            LOG.debug("Task delivery thread is going to sleep, there are no tasks waiting for delivery.");
            logQueueStatus();
            wait();
        }
        LOG.debug("Task delivery thread is awake and there are some undelivered tasks.");
        logQueueStatus();

        // Circular lists retain iteration state via their head pointers.
        Job job = jobs.advanceToElement(e -> e.visibleTasks.size() > 0);

        // We have found job with some undelivered tasks. Give them to a consumer,
        // waiting until one is available even if this means ignoring graph affinity.
        LOG.debug("Task delivery thread has found undelivered tasks in job {}.", job.jobId);
        while (true) {
            while (nWaitingConsumers == 0) {
                LOG.debug("Task delivery thread is going to sleep, there are no consumers waiting.");
                // Thread will be notified when there are new incoming consumer connections.
                wait();
            }
            LOG.debug("Task delivery thread is awake, and some consumers are waiting.");
            logQueueStatus();

            // Here, we know there are some consumer connections waiting, but we're not sure they're still open.
            // First try to get a consumer with affinity for this graph
            LOG.debug("Looking for an eligible consumer, respecting graph affinity.");
            Deque<Response> deque = consumersByGraph.get(job.graphId);
            while (deque != null && !deque.isEmpty()) {
                Response response = deque.pop();
                nWaitingConsumers -= 1;
                if (deliver(job, response)) {
                    return;
                }
            }

            // Then try to get a consumer from the graph with the most workers
            LOG.debug("No consumers with the right affinity. Looking for any consumer.");
            List<Deque<Response>> deques = new ArrayList<>(consumersByGraph.values());
            deques.sort((d1, d2) -> Integer.compare(d2.size(), d1.size()));
            for (Deque<Response> d : deques) {
                while (!d.isEmpty()) {
                    Response response = d.pop();
                    nWaitingConsumers -= 1;
                    if (deliver(job, response)) {
                        return;
                    }
                }
            }

            // No workers were available to accept the tasks. The thread should wait on the next iteration.
            LOG.debug("No consumer was available. They all must have closed their connections.");
            if (nWaitingConsumers != 0) {
                throw new AssertionError("There should be no waiting consumers here, something is wrong.");
            }
        }

    }

    /**
     * Attempt to hand some tasks from the given job to a waiting consumer connection.
     * The write will fail if the consumer has closed the connection but it hasn't been removed from the connection
     * queue yet because the Broker methods are synchronized (the removal action is waiting to get the monitor).
     * @return whether the handoff succeeded.
     */
    public synchronized boolean deliver (Job job, Response response) {

        // Check up-front whether the connection is still open.
        if (!response.getRequest().getRequest().getConnection().isOpen()) {
            LOG.debug("Consumer connection was closed. It will be removed.");
            return false;
        }

        // Get up to N tasks from the visibleTasks deque
        List<AnalystClusterRequest> tasks = new ArrayList<>();
        while (tasks.size() < 4 && !job.visibleTasks.isEmpty()) {
            tasks.add(job.visibleTasks.poll());
        }

        // Attempt to deliver the tasks to the given consumer.
        try {
            response.setStatus(HttpStatus.OK_200);
            OutputStream out = response.getOutputStream();
            mapper.writeValue(out, tasks);
            response.resume();
        } catch (IOException e) {
            // The connection was probably closed by the consumer, but treat it as a server error.
            LOG.debug("Consumer connection caused IO error, it will be removed.");
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.resume();
            // Delivery failed, put tasks back on (the end of) the queue.
            job.visibleTasks.addAll(tasks);
            return false;
        }

        // Delivery succeeded, move tasks from undelivered to delivered status
        LOG.debug("Delivery of {} tasks succeeded.", tasks.size());
        nUndeliveredTasks -= tasks.size();
        job.markTasksDelivered(tasks);
        return true;

    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not be re-delivered.
     * @return whether the task was found and removed.
     */
    public synchronized boolean deleteJobTask (int taskId) {
        // There could be thousands of invisible (delivered) tasks, so we use a hash map.
        // We only allow removal of delivered, invisible tasks for now (not undelivered tasks).
        // Return whether removal call discovered an existing task.
        return deliveredTasks.remove(taskId) != null;
    }

    /**
     * Marks the specified priority request as completed, and returns the suspended Response object for the connection
     * that submitted the priority request, and is likely still waiting for a result over the same connection.
     * The HttpHandler thread can then pump data from the DELETE body back to the origin of the request,
     * without blocking the broker thread.
     */
    public synchronized Response deletePriorityTask (int taskId) {
        return priorityResponses.remove(taskId);
    }

    // Todo: occasionally purge closed connections from consumersByGraph

    @Override
    public void run() {
        while (true) {
            try {
                deliverTasksForOneJob();
            } catch (InterruptedException e) {
                LOG.warn("Task pump thread was interrupted.");
                return;
            }
        }
    }

    public Job findJob (AnalystClusterRequest task) {
        for (Job job : jobs) {
            if (job.jobId.equals(task.jobId)) {
                return job;
            }
        }
        Job job = new Job(task.jobId);
        job.graphId = task.graphId;
        jobs.insertAtTail(job);
        return job;
    }

}
