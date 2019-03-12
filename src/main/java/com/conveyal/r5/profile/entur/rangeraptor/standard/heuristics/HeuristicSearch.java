package com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;

import java.util.Collection;

/**
 * Combine Heuristics and Worker into one class to be able to retrieve the
 * heuristics after the worker is invoked.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class HeuristicSearch<T extends TripScheduleInfo> implements Worker<T> {
    private final Heuristics heuristics;
    private final Worker<T> worker;

    public HeuristicSearch(Heuristics heuristics, Worker<T> worker) {
        this.heuristics = heuristics;
        this.worker = worker;
    }

    public Heuristics heuristics() {
        return heuristics;
    }

    @Override
    public Collection<Path<T>> route() {
        return worker.route();
    }
}
