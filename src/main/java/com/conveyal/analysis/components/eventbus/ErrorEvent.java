package com.conveyal.analysis.components.eventbus;

import com.conveyal.r5.util.ExceptionUtils;

/**
 * This Event is fired each time a Throwable (usually an Exception or Error) occurs on the backend. It can then be
 * recorded or tracked in various places - the console logs, Slack, etc. This could eventually be used for errors on
 * the workers as well, but we'd have to be careful not to generate hundreds of messages at once.
 */
public class ErrorEvent extends Event {

    // We may serialize this object, so we convert the Throwable to two strings to control its representation.
    // For flexibility in event handlers, it is tempting to hold on to the original Throwable instead of derived
    // Strings. Exceptions are famously slow, but it's the initial creation and filling in the stack trace that are
    // slow. Once the instace exists, repeatedly examining its stack trace should not be prohibitively costly. Still,
    // we do probably gain some efficiency by converting the stack trace to a String once and reusing that.

    public final String summary;

    /**
     * The path portion of the HTTP URL, if the error has occurred while responding to an HTTP request from a user.
     * May be null if this information is unavailable or unknown (in components where user information is not retained).
     */
    public final String httpPath;

    public final String stackTrace;

    public ErrorEvent (Throwable throwable, String httpPath) {
        this.summary = ExceptionUtils.shortCauseString(throwable);
        this.stackTrace = ExceptionUtils.stackTraceString(throwable);
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
            builder.append(filterStackTrace(stackTrace));
        }
        return builder.toString();
    }

    private static String filterStackTrace (String stackTrace) {
        if (stackTrace == null) return null;
        final String unknownFrame = "at unknown frame";
        String error = stackTrace.lines().findFirst().get();
        String frame = stackTrace.lines()
                .map(String::strip)
                .filter(s -> s.startsWith("at "))
                .findFirst().orElse(unknownFrame);
        String conveyalFrame = stackTrace.lines()
                .map(String::strip)
                .filter(s -> s.startsWith("at com.conveyal."))
                .filter(s -> !frame.equals(s))
                .findFirst().orElse("");
        return String.join("\n", error, frame, conveyalFrame);
    }

}
