package com.conveyal.analysis.components.broker;

import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A catalog of all the workers this broker has been contacted by recently.
 * Ideally this would also manage target quantities of workers by category and migrate workers from one category to
 * another. But for now we just leave workers on a single graph / r5 commit and don't migrate them.
 */
public class WorkerCatalog {

    public static final int WORKER_RECORD_DURATION_MSEC = 2 * 60 * 1000;

    /**
     * The information supplied by workers the last time they polled for more tasks.
     * We store these observations keyed on the worker ID so new observations replace old ones for the same worker.
     */
    private Map<String, WorkerObservation> observationsByWorkerId = new HashMap<>();

    /**
     * Keeps the workers sorted into categories depending on which network and R5 commit they are running.
     * The values are worker IDs instead of observations because the observation objects are being constantly updated,
     * so are not practical for equality tests and hashing.
     */
    private Multimap<WorkerCategory, String> workerIdsByCategory = HashMultimap.create();

    /**
     * Tracks which worker within each category we are directing single point requests to.
     * This is a trade-off: single point work is not balanced between workers, but once a worker applies scenarios
     * and links pointsets that work will not need to be redone for subsequent single point requests.
     */
    private Map<WorkerCategory, String> singlePointWorkerIdByCategory = new HashMap<>();

    /**
     * Record the fact that a worker with a particular ID was just observed polling for tasks.
     */
    public synchronized void catalog (WorkerStatus workerStatus) {
        String workerId = workerStatus.workerId;
        WorkerObservation observation = new WorkerObservation(workerStatus);
        WorkerObservation oldObservation = observationsByWorkerId.put(workerId, observation);
        if (oldObservation != null) {
            // A worker with this ID has been seen before. The worker may have changed category.
            // Remove the worker from its previous category before adding it to the new category.
            if ( ! observation.category.equals(oldObservation.category)) {
                workerIdsByCategory.remove(oldObservation.category, workerId);
                singlePointWorkerIdByCategory.remove(oldObservation.category, workerId);
            }
        }
        // Associate the worker with its currently reported category. According to Guava docs, Multimap does not store
        // duplicate key-value pairs. Adding a new key-value pair equal to an existing key-value pair has no effect.
        workerIdsByCategory.put(observation.category, workerId);
        // If this worker's current category has no assigned single point worker, assign this worker.
        if (singlePointWorkerIdByCategory.get(observation.category) == null) {
            singlePointWorkerIdByCategory.put(observation.category, observation.workerId);
        }
    }

    /**
     * Before fetching worker information, call this method to remove any workers that we haven't heard from for a
     * while.
     * Perhaps we should be calling this method on a timer instead of every time read-oriented methods are called.
     */
    private synchronized void purgeDeadWorkers () {
        long now = System.currentTimeMillis();
        long oldestAcceptable = now - WORKER_RECORD_DURATION_MSEC;
        List<WorkerObservation> ancientObservations = new ArrayList<>();
        for (WorkerObservation observation : observationsByWorkerId.values()) {
            if (observation.lastSeen < oldestAcceptable) {
                ancientObservations.add(observation);
            }
        }
        for (WorkerObservation observation : ancientObservations) {
            observationsByWorkerId.remove(observation.workerId);
            workerIdsByCategory.remove(observation.category, observation.workerId);
            singlePointWorkerIdByCategory.remove(observation.category, observation.workerId);
        }
    }

    /**
     * If a single-point worker has shut down, single-point work for a given workerCategory should be re-assigned. If
     * no other workers of the requested workerCategory exist, the caller in the Broker should start one.
     *
     * @param workerCategory of worker that could not be reached for a single-point request (and has presumably shut
     *                       down)
     */
    public synchronized void tryToReassignSinglePointWork (WorkerCategory workerCategory) {
        String oldSinglePointWorkerId = singlePointWorkerIdByCategory.get(workerCategory);

        // Clear the shut down worker from places it had been cataloged
        singlePointWorkerIdByCategory.remove(workerCategory);
        observationsByWorkerId.remove(oldSinglePointWorkerId);
        workerIdsByCategory.remove(workerCategory, oldSinglePointWorkerId);

        // If another worker for this workerCategory is running, set it to handle single-point work.
        if (workerIdsByCategory.get(workerCategory).size() > 0) {
            String nextWorkerId = workerIdsByCategory.get(workerCategory).iterator().next();
            singlePointWorkerIdByCategory.put(workerCategory, nextWorkerId);
        }
    }

    /**
     * Return the address of a worker machine on a given category that can be used to handle a single point request.
     * Attempt to repeatedly return the same active worker for a given category.
     * This helps direct single point requests to the same worker and avoid relinking.
     * The HashMultimap does not return values in a predictable order, so we maintain a separate map for these workers.
     */
    public synchronized String getSinglePointWorkerAddressForCategory(WorkerCategory workerCategory) {
        purgeDeadWorkers();
        String workerId = singlePointWorkerIdByCategory.get(workerCategory);
        if (workerId == null) return null;
        return observationsByWorkerId.get(workerId).status.ipAddress;
    }

    public synchronized int totalWorkerCount() {
        return observationsByWorkerId.size();
    }

    public synchronized int countWorkersInCategory(WorkerCategory workerCategory){
        return workerIdsByCategory.get(workerCategory).size();
    }

    /**
     * Count active workers for all jobs and return a protective copy of the list for further processing in the caller,
     * in order to minimize the time spent in this synchronized block.
     *
     * A worker is considered active on a job if the worker reported a task complete for that job over the minute before
     * it last polled the backend.
     *
     * @return Map jobId to total workers "active on that job".
     */
    public synchronized TObjectIntMap<String> activeWorkersPerJob() {
        Collection<WorkerObservation> observations = getAllWorkerObservations();
        TObjectIntMap<String> workersPerJobPerMinute = new TObjectIntHashMap<>();
        for (WorkerObservation wo : observations) {
            for (Map.Entry<String, Integer> pair : wo.status.tasksPerMinuteByJobId.entrySet()) {
                if (pair.getValue() > 0) {
                    workersPerJobPerMinute.adjustOrPutValue(pair.getKey(), 1, 1);
                }
            }
        }
        return workersPerJobPerMinute;
    }

    /**
     * TODO should this return a protective copy? For now it's synchronized like all other methods.
     */
    public synchronized Collection<WorkerObservation> getAllWorkerObservations() {
        purgeDeadWorkers();
        return observationsByWorkerId.values();
    }

    public synchronized boolean noWorkersAvailable(WorkerCategory category, boolean ignoreWorkerVersion) {
        purgeDeadWorkers();
        if (ignoreWorkerVersion) {
            // Look for workers on the right network ID, independent of their worker software version.
            return observationsByWorkerId.values().stream().noneMatch(obs -> obs.category.graphId.equals(category.graphId));
        }
        return workerIdsByCategory.get(category).isEmpty();
    }

}
