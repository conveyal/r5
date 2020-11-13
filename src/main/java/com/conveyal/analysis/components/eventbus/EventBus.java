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

    // For handlers to listen for a hierarchy of events, a hashtable would require one entry per class in the hierarchy.
    // Linear scan through handlers is simpler and should be at least as efficient for small numbers of handlers.
    private final List<EventHandler> handlers = new ArrayList<>();

    public EventBus (TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /** This is not synchronized, so you should add all handlers at once before any events are fired. */
    public void addHandlers (EventHandler... handlers) {
        for (EventHandler handler : handlers) {
            LOG.info("An instance of {} will receive events.", handler.getClass().getSimpleName());
            this.handlers.add(handler);
        }
    }

    public <T extends Event> void send (final T event) {
        LOG.debug("Bus received event: {}", event);
        for (EventHandler handler : handlers) {
            final boolean accept = handler.acceptEvent(event);
            final boolean synchronous = handler.synchronous();
            LOG.debug(" -> {} {} handler {}", accept ? "firing":"skipping", synchronous ? "sync":"async", handler);
            if (accept) {
                if (synchronous) {
                    try {
                        handler.handleEvent(event);
                    } catch (Throwable t) {
                        LOG.error("Event handler failed: {}", t.toString());
                        t.printStackTrace();
                    }
                } else {
                    taskScheduler.enqueueLightTask(() -> handler.handleEvent(event));
                }
            }
        }
    }

}
