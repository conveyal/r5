package com.conveyal.r5.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Convenience functions for working with exceptions.
 */
public abstract class ExceptionUtils {

    public static String asString(Exception exception) {
        StringWriter sw = new StringWriter();
        sw.append(exception.getMessage());
        sw.append("\n");
        exception.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

}
