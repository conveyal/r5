package com.conveyal.r5.analyst.broker;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A catalog of all the workers this broker has been contacted by recently.
 * Ideally this would also manage target quantities of workers by category and migrate workers from one category to
 * another. But for now we just leave workers on a single graph / r5 commit and don't migrate them.
 */
public class WorkerCatalog {

    public static final int WORKER_RECORD_DURATION_MSEC = 2 * 60 * 1000;

    /** Keeps track of the last time a worker polled the broker for more tasks. */
    Map<String, WorkerObservation> observationsByWorkerId = new HashMap<>();

    /** Keeps the workers sorted into categories depending on which network and R5 commit they are running. */
    Multimap<WorkerCategory, String> workersByCategory = HashMultimap.create();

    /**
     * Record the fact that a worker with a particular ID was just seen connecting to the broker.
     */
    public synchronized void catalog (String workerId, WorkerCategory category) {
        WorkerObservation observation = new WorkerObservation(workerId, category);
        WorkerObservation oldObservation = observationsByWorkerId.put(workerId, observation);
        if (oldObservation != null) {
            workersByCategory.remove(oldObservation.category, workerId);
        }
        workersByCategory.put(category, workerId);
    }

    public synchronized void purgeDeadWorkers () {
        long now = System.currentTimeMillis();
        long oldestAcceptable = now - WORKER_RECORD_DURATION_MSEC;
        List<WorkerObservation> ancientObservations = observationsByWorkerId.values().stream()
                .filter(observation -> observation.lastSeen < oldestAcceptable).collect(Collectors.toList());
        ancientObservations.forEach(observation -> {
            observationsByWorkerId.remove(observation.workerId);
            workersByCategory.remove(observation.category, observation.workerId);
        });
    }

    public int size () {
        return workersByCategory.size();
    }

}
