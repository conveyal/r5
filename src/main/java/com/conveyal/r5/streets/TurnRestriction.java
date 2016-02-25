package com.conveyal.r5.streets;

import java.io.Serializable;

/**
 * Represents a turn restriction.
 */
public class TurnRestriction implements Serializable {
    public static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final long serialVersionUID = -1;

    /** is this an only-turn restriction? */
    public boolean only;

    /** the edge at which this turn restriction starts */
    public int fromEdge;

    /** the edge we're turning onto */
    public int toEdge;

    /** the intermediate edges in this turn restriction */
    public int[] viaEdges = EMPTY_INT_ARRAY;

    // via information is implied by the edges this turn restriction is attached to
}
