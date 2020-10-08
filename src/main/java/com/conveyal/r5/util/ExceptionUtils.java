package com.conveyal.r5.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Convenience functions for working with exceptions (or more generally throwables).
 */
public abstract class ExceptionUtils {

    public static String asString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        sw.append(throwable.getMessage());
        sw.append("\n");
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

}
