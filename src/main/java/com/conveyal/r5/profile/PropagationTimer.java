package com.conveyal.r5.profile;

/**
 * This groups together all the timers recording execution time of various steps of travel time propagation, which
 * is performed after the raptor search itself.
 *
 * TODO constructor that adds stop and target counts to top level message
 *         LOG.info("Propagating {} iterations from {} stops to {} target points took {}s",
 *                 nIterations, nStops, endTarget - startTarget, (System.currentTimeMillis() - startTimeMillis) / 1000d
 *         );
 */
public class PropagationTimer {

    public final ExecutionTimer fullPropagation = new ExecutionTimer("Full travel time propagation");

    public final ExecutionTimer transposition = new ExecutionTimer(fullPropagation, "Travel time matrix transposition");

    public final ExecutionTimer propagation = new ExecutionTimer(fullPropagation, "Propagation");

    public final ExecutionTimer reducer = new ExecutionTimer(fullPropagation, "Travel time reducer");

    public void log () {
        fullPropagation.logWithChildren();
    }

}
