package com.conveyal.r5.profile.entur.util;

import java.util.Arrays;

/**
 * A utility class for integer functions.
 */
public final class IntUtils {
    /** The constructor is private to protect this class from being instantiated. */
    private IntUtils() {};


    /**
     * Convert an integer to a String, if the value equals the {@code notSetValue} parameter
     * an empty string is returned.
     */
    public static String intToString(int value, int notSetValue) {
        return value == notSetValue ? "" : Integer.toString(value);
    }


    /**
     * Create a new int array an initialize it with the given {@code initialValue}.
     */
    public static int[] intArray(int size, int initialValue) {
        int [] array = new int[size];
        Arrays.fill(array, initialValue);
        return array;
    }
}
