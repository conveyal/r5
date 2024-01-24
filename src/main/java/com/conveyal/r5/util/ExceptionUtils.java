package com.conveyal.r5.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Convenience functions for working with exceptions (or more generally throwables).
 */
public abstract class ExceptionUtils {

    /**
     * Returns the output of Throwable.printStackTrace() in a String.
     * This is the usual Java stack trace we're accustomed to seeing on the console.
     * The throwable.printStackTrace method includes the class name and detail message, and will traverse the whole
     * chain of causes showing multiple stack traces, avoiding any reference loops. The resulting string will contain
     * linefeeds and tabs which must be properly handled when displaying (e.g. in HTML).
     */
    public static String stackTraceString (Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Short-form exception summary that includes the chain of causality, reversed such that the root cause comes first.
     * We might want to add line numbers of one stack frame with class simple names.
     */
    public static String shortCauseString (Throwable throwable) {
        List<String> items = new ArrayList<>();
        Set<Throwable> seen = new HashSet<>(); // Bail out if there are cycles in the cause chain
        while (throwable != null && !seen.contains(throwable)) {
            String item = throwable.getClass().getSimpleName();
            if (throwable.getMessage() != null) {
                item += ": " + throwable.getMessage();
            }
            items.add(item);
            seen.add(throwable);
            throwable = throwable.getCause();
        }
        Collections.reverse(items);
        return String.join(", caused ", items);
    }

    public static String shortAndLongString (Throwable throwable) {
        return shortCauseString(throwable) + "\n[detail follows]\n" + stackTraceString(throwable);
    }

    /**
     * Given a full stack trace string with one frame per line, keep only the exception name, the first stack frame,
     * and all additional frames that come from Conveyal packages. This yields a much shorter stack trace that still
     * shows where the exception was thrown and where the problem originates in our own code.
     */
    public static String filterStackTrace (Throwable throwable) {
        String stackTrace = stackTraceString(throwable);
        final String unknownFrame = "Unknown stack frame, probably optimized out by JVM.";
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
