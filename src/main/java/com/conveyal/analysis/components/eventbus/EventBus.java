package com.conveyal.analysis.components.eventbus;

import com.conveyal.analysis.components.Component;
import com.conveyal.analysis.components.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Sometimes a custom component needs to receive notifications from standard components. The standard component does
 * not know about the custom component, so cannot call it directly. The standard component can export a listener
 * interface and mechanisms to register and call listeners, but then this logic has to be repeated on any class that
 * has sends events to listeners. An event bus is essentially shared listener registration across all components.
 *
 * By default execution of the handlers receiving events is asynchronous (handled by a shared pool of threads).
 * An alternate send method exists for synchronous handling, in the thread that sent the event. One must be careful not
 * to trigger self-amplifying event loops which amount to infinite recursion in the synchronous case, and an
 * ever-expanding event queue in the async case. Therefore event handlers should never themselves trigger more events.
 */
public class EventBus implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final TaskScheduler taskScheduler;

    // private final Multimap<Class<? extends Event>, EventHandler<? extends Event>> eventHandlers;
    // For handlers to listen for a hierarchy of events, a hashtable would require one entry per class in the hierarchy.
    // Linear scan through handlers, and should be at least as efficient for small numbers of handlers.
    private final List<Listener> eventListeners = new ArrayList<>();

    public EventBus (TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    public interface EventHandler<E extends Event> {
        void handleEvent (E event);
    }

    /**
     * Note: this method is not synchronized. Add all handlers before any events are sent.
     * Ideally we'd register them all at once as an immutable list.
     */
    public <E extends Event> void listenFor (Class<E> eventType, EventHandler<E> eventHandler, boolean synchronous) {
        eventListeners.add(new Listener(eventType, eventHandler, synchronous));
    }

    public <T extends Event> void send (final T event) {
        LOG.debug("Received event: {}", event);
        for (Listener listener : eventListeners) {
            if (listener.eventType.isInstance(event)) {
                listener.send(event);
            }
        }
    }

    private class Listener<T extends Event> {

        final Class<T> eventType;
        final EventHandler<T> eventHandler;
        final boolean synchronous;

        public Listener (Class<T> eventType, EventHandler<T> eventHandler, boolean synchronous) {
            this.eventType = eventType;
            this.eventHandler = eventHandler;
            this.synchronous = synchronous;
        }

        public void send (T event) {
            LOG.debug(" -> firing {} handler {}", synchronous ? "sync" : "async", eventHandler);
            if (synchronous) {
                try {
                    eventHandler.handleEvent(event);
                } catch (Throwable t) {
                    LOG.error("Event handler failed: {}", t.toString());
                    t.printStackTrace();
                }
            } else {
                taskScheduler.enqueueLightTask(() -> eventHandler.handleEvent(event));
            }
        }
    }

}
