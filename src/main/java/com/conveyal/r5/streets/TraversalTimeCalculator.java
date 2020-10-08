package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;

import java.io.Serializable;

/**
 * This is intended to serve as a generalization of the existing travel time and turn cost calculations, as well as the
 * generalized cost modules developed for LADOT. Calculator implementations should be threadsafe as they will be used
 * for different trip planning requests in different threads simultaneously.
 *
 * This must be used for traversing entire edges or just fractions of edges (in the temporary Splits used at the
 * beginning and end of routing, and in linked PointSets). For sophisticated time computations (for example with
 * complete elevation profiles) the proportion would need to be passed into the implementation for accuracy.
 * But for now the caller can just scale the full-edge traversal time.
 */
public interface TraversalTimeCalculator extends Serializable {

    /**
     * Calculate the time to travel down the edge using the given streetMode, considering speed settings in the given
     * request. We need both the request and the streetMode because the request contains the speeds for different modes,
     * but the mode often changes from bike or car to walk at different points during a search.
     *
     * The implementation should not move the supplied edge cursor to a different edge, only read from it.
     * @param currentEdge the edge currently being traversed.
     * @return the time it takes to travel down the entire edge using the streetMode, considering the request settings.
     */
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req);

    /**
     * Calculate the time to turn from fromEdge onto toEdge by the given streetMode, considering settings in the given
     * request.
     *
     * The caller may have an already instantiated edge cursor, but depending on search direction this may be positioned
     * at the first or second edge in the turn, and if it is treated as immutable another cursor may need to be created
     * by the implementation. To simplify things we simply supply edge indexes for both edges involved in the turn
     * (rather than a cursor for one of them); object creation and cleanup is very cheap with modern garbage collectors.
     *
     * @return the expected value of the time in seconds to make the turn.
     */
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode);


}
