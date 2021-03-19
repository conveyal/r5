package com.conveyal.r5.util;

import java.io.PrintWriter;
import java.io.StringWriter;

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

}
