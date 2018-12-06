package com.conveyal.r5.profile.entur.util;

import java.util.Arrays;

/**
 * TODO TGR
 */
public final class IntUtils {
    /** protect this class from being instantiated. */
    private IntUtils() {};

    public static int[] newIntArray(int size, int initalValue) {
        int [] array = new int[size];
        Arrays.fill(array, initalValue);
        return array;
    }

    public static String intToString(int value, int notSetValue) {
        return value == notSetValue ? "" : Integer.toString(value);
    }
}
