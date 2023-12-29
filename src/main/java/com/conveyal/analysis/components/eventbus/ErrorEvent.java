package com.conveyal.analysis.components.eventbus;

import com.conveyal.r5.util.ExceptionUtils;

import static com.conveyal.r5.util.ExceptionUtils.filterStackTrace;

/**
 * This Event is fired each time a Throwable (usually an Exception or Error) occurs on the backend. It can then be
 * recorded or tracked in various places - the console logs, Slack, etc. This could eventually be used for errors on
 * the workers as well, but we'd have to be careful not to generate hundreds of messages at once.
 */
public class ErrorEvent extends Event {

    // All Events are intended to be eligible for serialization into a log or database, so we convert the Throwable to
    // some Strings to determine its representation in a simple way.
    // For flexibility in event handlers, it is tempting to hold on to the original Throwable instead of derived
    // Strings. Exceptions are famously slow, but it's the initial creation and filling in the stack trace that are
    // slow. Once the instance exists, repeatedly examining its stack trace should not be prohibitively costly.

    public final String summary;

    /**
     * The path portion of the HTTP URL, if the error has occurred while responding to an HTTP request from a user.
     * May be null if this information is unavailable or unknown (in components where user information is not retained).
     */
    public final String httpPath;

    /** The full stack trace of the exception that occurred. */
    public final String stackTrace;

    /** A minimal stack trace showing the immediate cause within Conveyal code. */
    public final String filteredStackTrace;

    public ErrorEvent (Throwable throwable, String httpPath) {
        this.summary = ExceptionUtils.shortCauseString(throwable);
        this.stackTrace = ExceptionUtils.stackTraceString(throwable);
        this.filteredStackTrace = ExceptionUtils.filterStackTrace(throwable);
        this.httpPath = httpPath;
    }

    public ErrorEvent (Throwable throwable) {
        this(throwable, null);
    }

    /** Return a string intended for logging on Slack or the console. */
    public String traceWithContext (boolean verbose) {
        StringBuilder builder = new StringBuilder();
        if (user == null && accessGroup == null) {
            builder.append("Unknown/unauthenticated user");
        } else {
            builder.append("User ");
            builder.append(user);
            builder.append(" of group ");
            builder.append(accessGroup);
        }
        if (httpPath != null) {
            builder.append(" accessing ");
            builder.append(httpPath);
        }
        builder.append(": ");
        if (verbose) {
            builder.append(stackTrace);
        } else {
            builder.append(filteredStackTrace);
        }
        return builder.toString();
    }

}
