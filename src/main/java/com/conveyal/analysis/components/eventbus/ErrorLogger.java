package com.conveyal.analysis.components.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log selected events to the console. Mostly to ensure a redundant record of severe errors.
 * All other observations on throughput, HTTP API requests etc. can be recorded in more structured ways.
 */
public class ErrorLogger implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorLogger.class);

    @Override
    public void handleEvent (Event event) {
        if (event instanceof ErrorEvent) {
            ErrorEvent errorEvent = (ErrorEvent) event;
            LOG.error("User {} of {}: {}", errorEvent.user, errorEvent.accessGroup, errorEvent.stackTrace);
        }
    }

    @Override
    public boolean acceptEvent (Event event) {
        return event instanceof ErrorEvent;
    }

    @Override
    public boolean synchronous () {
        // Log call is very fast and we want to make sure it happens. Do not hand off to another thread.
        return true;
    }

}
