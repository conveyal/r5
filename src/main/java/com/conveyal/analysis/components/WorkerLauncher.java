package com.conveyal.analysis.components;

import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.r5.analyst.WorkerCategory;

/// Interface for Components that start workers.
public interface WorkerLauncher extends Component {

    /// Start worker instances to handle single point or regional tasks.
    public void launch (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot);

    /// Return how many workers were recently requested in the given category and are presumed to
    /// still be starting up. This is a count of requests, not of machines known to exist.
    /// Even when workers have been requested or launched subsequent steps may still fail (boot,
    /// software installation, connection to the backend). Implementations should therefore apply
    /// some expiration policy to workers that never show up. A worker is only known to exist in
    /// a usable form when it polls the backend, at which point it appears in the worker catalog.
    /// Default implementation reports nothing pending, for immediately starting workers locally.
    default int pendingWorkersInCategory (WorkerCategory category) {
        return 0;
    }

}
