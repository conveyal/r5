package com.conveyal.analysis.components;

/**
 * This is a stub abstraction for a component performing accessibility analysis computations.
 * Computation could be on remote or local hardware, using code in this repo or a dependency.
 * This would encapsulate any logic that manages a compute cluster.
 * In practice, the backend currently relays the requests to a cluster of local or remote workers running R5.
 */
public class Compute {

    public void handleRequest () {

    }

    /** Also allow swappable implementation of worker polling to avoid doing it over HTTP locally? */
    public void workerPollForWork() {

    }


}
