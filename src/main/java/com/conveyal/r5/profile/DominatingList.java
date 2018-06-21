package com.conveyal.r5.profile;

import java.util.Collection;

/**
 * When performing a multi-criteria search that finds sets of pareto-optimal paths (which is essential for resource
 * constraint situations) we must allow for multiple search states at each vertex (in this case transit stops).
 * One state is said to dominate another when it is better than that other state in all ways, i.e. it provides a path
 * that is so much better that the other path can be pruned or abandoned.
 *
 * When you add states to this type of list, the state might not actually be added if another state in the list
 * dominates it. If the state is optimal and is added to the list, other states are automatically dropped if they
 * are no longer co-optimal given the existence of the new state.
 *
 * It is assumed that all states inserted are comparable, meaning that they are at the same location and part of the
 * same search when simultaneously conducting multiple searches e.g. for different access modes.
 *
 * We only do multi-criteria transit searches, we haven't implemented multi-criteria street searches.
 */
public interface DominatingList {
    /** Attempt to add a state to this dominating list, and evict dominated states, returning true if this state is
     * undominated */
    boolean add (McRaptorSuboptimalPathProfileRouter.McRaptorState state);

    /** get non-dominated states at this location */
    Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates ();
}
