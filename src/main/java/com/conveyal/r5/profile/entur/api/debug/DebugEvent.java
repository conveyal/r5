package com.conveyal.r5.profile.entur.api.debug;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;


/**
 * Debug events hold information about an internal event in the Raptor Algorithm. The element
 * may be a stop arrivals, a destination arrival or path.
 *
 * @param <E> the element type.
 */
public class DebugEvent<E> {
    private final Action action;
    private final int iterationStartTime;
    private final E element;
    private final E droppedByElement;
    private final Collection<E> result;


    /**
     * Private constructor; use static factroy methods to create events.
     */
    private DebugEvent(
            Action action,
            int iterationStartTime,
            E element,
            E droppedByElement,
            Collection<? extends E> result
    ) {
        this.action = action;
        this.iterationStartTime = iterationStartTime;
        this.element = element;
        this.droppedByElement = droppedByElement;
        this.result = result == null ? Collections.emptyList() : new ArrayList<>(result);
    }

    public static <E> DebugEvent<E> accept(int iterationStartTime, E element, Collection<? extends E> result) {
        return new DebugEvent<>(Action.ACCEPT, iterationStartTime, element, null, result);
    }

    public static <E> DebugEvent<E> reject(int iterationStartTime, E element, Collection<? extends E> result) {
        return new DebugEvent<>(Action.REJECT, iterationStartTime, element, null, result);
    }

    public static <E> DebugEvent<E> rejectByOptimization(int iterationStartTime, E element) {
        return new DebugEvent<>(Action.REJECT_OPTIMIZED, iterationStartTime, element, null, null);
    }

    public static <E> DebugEvent<E> drop(int iterationStartTime, E element, E droppedByElement) {
        return new DebugEvent<>(Action.DROP, iterationStartTime, element, droppedByElement, null);
    }

    /**
     * The acton taken:
     * <ul>
     *     <li>ACCEPT - The element is accepted as one of the best alternatives.
     *     <li>REJECT - The element is rejected, there is a better alternative {@link #result()}.
     *     <li>DROP   - The element is dropped from the list of alternatives, see {@link #droppedByElement()}. Be aware
     *     that that this does not necessarily mean that the path is not part of the final result. If an element is
     *     dropped in a later round or iteration the original element path might already be added to the final result;
     *     hence the drop event have no effect on the result.
     * </ul>
     */
    public Action action() {
        return action;
    }

    /**
     * Witch iteration this event is part of.
     */
    public int iterationStartTime() {
        return iterationStartTime;
    }

    /**
     * The element affected by the action.
     */
    public E element() {
        return element;
    }

    /**
     * The element was removed/dropped/dominated by the {@code  droppedByElement}. This may or may not affect
     * the final result depending on the round/iteration the original element was accepted and this event.
     */
    public E droppedByElement() {
        return droppedByElement;
    }

    /**
     * The state AFTER event happens, only available for ACCEPT and REJECT actions.
     * This is optional.
     *
     * @return The new state as a list of elements or {@code null} if not available.
     */
    public Collection<E> result() {
        return result;
    }

    /** The event action type */
    public enum Action {
        /** Element is accepted */
        ACCEPT("Accept"),
        /** Element is rejected */
        REJECT("Reject"),
        /** Element is rejected as part of an optimization. */
        REJECT_OPTIMIZED("Optimized"),
        /**
         * Element is dropped from the algorithm state. Since Range Raptor works in rounds and iterations, an element
         * dropped in a later round/iteration might still make it to the optimal solution. This only means that the
         * element is no longer part of the state.
         */
        DROP("Drop");
        private String description;
        Action(String description) { this.description = description; }
        @Override public String toString() { return description; }
    }
}
