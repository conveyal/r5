package com.conveyal.r5.profile;

import java.util.Collection;

/**
 * A list that handles domination automatically. It represents the state at a particular location and does not separate
 * by stop etc., it is assumed that all states inserted are comparable.
 */
public interface DominatingList {
    /** Attempt to add a state to this dominating list, returning true if the state is undominated */
    boolean add (McRaptorSuboptimalPathProfileRouter.McRaptorState state);

    /** get non-dominated states at this location */
    Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates ();
}
