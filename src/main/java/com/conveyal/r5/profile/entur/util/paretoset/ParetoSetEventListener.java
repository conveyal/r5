package com.conveyal.r5.profile.entur.util.paretoset;


import java.util.Collection;

/**
 * You may subscribe/listen to the {@link ParetoSet} for events by implementing this
 * interface and register it with the ParetoSet.
 * <p/>
 * When an element is accepted(added), other elements might get dropped. An element is dropped if it
 * is dominated by the new element.
 * <p/>
 * When an element is NOT accepted into the pareto set; it is rejected. The rejected element is
 * dominated by one or more existing elements.
 * <p/>
 * One {@link ParetoSet#add(Object)} operation may result in one accept event and zero to many dropped events.
 * <p/>
 * To subscribe to these events, implement this interface and register it with the {@link ParetoSet}.
 *
 * @param <T> Pareto Set element type
 */
public interface ParetoSetEventListener<T> {

    /**
     * This is the callback called when an element is dropped.
     */
    void notifyElementAccepted(T newElement, Collection<? extends T> resultSet);

    /**
     * This is the callback called when an element is dropped.
     */
    void notifyElementDropped(T element, T droppedByElement);

    /**
     * This is the callback called when an element is dropped.
     *
     * @param element     The new element that is rejected.
     * @param existingSet The existing elements in the set, in witch ataleast one element dominates
     *                    the new rejected element.
     */
    void notifyElementRejected(T element, Collection<? extends T> existingSet);
}
