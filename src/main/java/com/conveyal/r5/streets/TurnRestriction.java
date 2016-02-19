package com.conveyal.r5.streets;

import java.io.Serializable;

/**
 * Represents a turn restriction.
 */
public class TurnRestriction implements Serializable {
    private static final long serialVersionUID = -1;

    /** is this an only-turn restriction? */
    public boolean only;

    /** the edge at which this turn restriction starts */
    public int fromEdge;

    /** the edge we're turning onto */
    public int toEdge;

    // via information is implied by the edges this turn restriction is attached to
}
