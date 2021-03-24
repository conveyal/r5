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

    public final String stackTrace;

    public ErrorEvent (Throwable throwable) {
        this.summary = ExceptionUtils.shortCauseString(throwable);
        this.stackTrace = ExceptionUtils.stackTraceString(throwable);
    }

}
