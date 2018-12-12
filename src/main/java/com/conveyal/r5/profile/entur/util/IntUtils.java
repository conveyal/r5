package com.conveyal.r5.profile.entur.util;

/**
 * TODO TGR
 */
public final class IntUtils {
    /** protect this class from being instantiated. */
    private IntUtils() {};

    public static String intToString(int value, int notSetValue) {
        return value == notSetValue ? "" : Integer.toString(value);
    }
}
