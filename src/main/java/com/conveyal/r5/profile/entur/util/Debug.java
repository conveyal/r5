package com.conveyal.r5.profile.entur.util;

public final class Debug {
    private static boolean DEBUG = true;

    /** Do not instantiate. This is a utility class with static members only. */
    private Debug() {}

    public static void setDebug(boolean debug) {
        Debug.DEBUG = debug;
    }

    public static boolean isDebug() {
        return DEBUG;
    }
}
