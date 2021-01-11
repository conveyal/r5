package com.conveyal.analysis.components.eventbus;

/** Interface for classes that receive events. */
public interface EventHandler {

    /** Override this method to supply the event handling behavior. */
    void handleEvent (Event event);

    /** You can override this method to filter the events received by the handler. */
    default boolean acceptEvent (Event event) {
        return true;
    }

    /**
     * If the event handler function never blocks, is O(1) and very fast, this should return true.
     * Otherwise it should return false, so handling the event won't block the sender.
     */
    boolean synchronous ();

}
