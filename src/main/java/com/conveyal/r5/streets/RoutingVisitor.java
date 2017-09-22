package com.conveyal.r5.streets;

/**
 * A set of callbacks that the street router will invoke while it's routing, allowing you to observe its progress
 * and potentially stop the search.
 */
public interface RoutingVisitor {
    /** Called after search algorithms dequeue a vertex */
    void visitVertex(StreetRouter.State state);

    /**
     * Called right after visitVertex
     * @return true if search should stop
     */
    default boolean shouldBreakSearch() {
        return false;
    }
}
