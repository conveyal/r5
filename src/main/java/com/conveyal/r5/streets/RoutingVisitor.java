package com.conveyal.r5.streets;

/**
 * Interface for routing visitor
 *
 * Created by mabu on 23.8.2016.
 */
public interface RoutingVisitor {
    /**
     * Called after search algorithms dequeue a vertex
     */
    void visitVertex(StreetRouter.State state);

    /**
     * Called right after visitVertex
     *
     * @return true if search should stop
     */
    default boolean shouldBreakSearch() {
        return false;
    }
}
