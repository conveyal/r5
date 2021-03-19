package com.conveyal.r5.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
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
    public static String asString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Short-form exception summary that includes the chain of causality.
     * We might want to add line numbers of one stack frame with class simple names.
     */
    public static String shortCauseString (Throwable throwable) {
        StringWriter sw = new StringWriter();
        Set<Throwable> seen = new HashSet<>();
        while (throwable != null && !seen.contains(throwable)) {
            if (!seen.isEmpty()) {
                sw.append("\n Caused by ");
            }
            sw.append(throwable.getClass().getSimpleName());
            sw.append(": ");
            sw.append(throwable.getMessage());
            seen.add(throwable);
            throwable = throwable.getCause();
        }
        return sw.toString();
    }

}
