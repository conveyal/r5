package com.conveyal.r5.profile.entur.util.paretoset;


/**
 * You may subscribe/listen to the {@link ParetoSet} for drop events by implementing this
 * interface and register it with the ParetoSet.
 * <p/>
 * When an element is added to the pareto set, then existing elements are dropped from the set
 * if they are dominated by the new element. To get notified about such events you may register
 * a listener of this type.
 *
 * @param <T> Pareto Set element type
 */
@FunctionalInterface
public interface DropEventListener<T> {

    /**
     * This is the callback called when an element is dropped.
     */
    void elementDroppedFromParetoSet(T oldElement, T newElement);
}
