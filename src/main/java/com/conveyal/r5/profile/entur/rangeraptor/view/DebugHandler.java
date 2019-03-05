package com.conveyal.r5.profile.entur.rangeraptor.view;

import java.util.Collection;

/**
 * This interface serve as a debug handler for the Worker and State classes.
 * They ues this interface to report stop arrival events and destination arrival
 * events, but are not limited to those two.
 * <p/>
 * The implementation of this interface will take these events and report them
 * back to the API listeners, passed in as part of the debug request.
 *
 * @param <T> The element type reported to the handler
 */
public interface DebugHandler<T> {
    /**
     * Retuns TRUE if a listener exist and there the given stop index is in the stops or path list.
     */
    boolean isDebug(int stop);

    /**
     * Callback to notify that the given element is accepted into the given collection.
     * For example this happens when a new stop arrival is accepted at a particular stop.
     * <p/>
     * The handler will do the last check to see if this stop is in the request stop list
     * or in represent a path. To do this it traverses the path.
     */
    void accept(T element, Collection<? extends T> result);

    /**
     * Callback to notify that the given element is rejected by the given collection.
     * <p/>
     * The same check as in {@link #accept(Object, Collection)} is performed before reppoting
     * back to the API listeners.
     */
    void reject(T element, Collection<? extends T> result);

    /**
     * Callback to notify that the given element is rejected as part of an optimization.
     * An optimization might be that the arrivalTime or numberOfTransfer exceeds it limits.
     * <p/>
     * The same check as in {@link #accept(Object, Collection)} is performed before reppoting
     * back to the API listeners.
     */
    void rejectByOptimization(T element);

    /**
     * Callback to notify that the given element is dropped, because a new and even more
     * shiny element is found.
     * <p/>
     * The same check as in {@link #accept(Object, Collection)} is performed before reppoting
     * back to the API listeners.
     */
    void drop(T element, T droppedByElement);
}
