package com.conveyal.r5.common;

import java.util.Collection;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class Util {

    public static String human (double n, String units) {
        String prefix = "";
        if (n > 1024) {
            n /= 1024;
            prefix = "ki";
        }
        if (n > 1024) {
            n /= 1024;
            prefix = "Mi";
        }
        if (n > 1024) {
            n /= 1024;
            prefix = "Gi";
        }
        if (n > 1024) {
            n /= 1024;
            prefix = "Ti";
        }
        return String.format("%1.1f %s%s", n, prefix, units);
    }

    public static boolean isNullOrEmpty (Collection collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean notNullOrEmpty (Collection collection) {
        return !isNullOrEmpty(collection);
    }

    public static <T> boolean isNullOrEmpty (T[] array) {
        return array == null || array.length == 0;
    }

    public static <T> boolean notNullOrEmpty (T[] array) {
        return !isNullOrEmpty(array);
    }

    public static boolean isNullOrEmpty (int[] array) {
        return array == null || array.length == 0;
    }

    public static <T> boolean notNullOrEmpty (int[] array) {
        return !isNullOrEmpty(array);
    }

    /** Convenience method to create an array and fill it immediately with a single value. */
    public static int[] newIntArray (int length, int defaultValue) {
        int[] array = new int[length];
        Arrays.fill(array, defaultValue);
        return array;
    }

    /**
     * Interpret bits lowBitPos and lowBitPos + 1 of a 32 bit int as a 2-bit unsigned int,
     * giving values from zero to 3.
     */
    public static int readTwoBits (int bitField, int lowBitPos) {
        return (bitField >> lowBitPos) & 0b11;
    }

    /**
     * Interpret bits lowBitPos and lowBitPos + 1 of a 32 bit int as a 2-bit unsigned int,
     * giving values from zero to 3.
     */
    public static int writeTwoBits (int bitField, int lowBitPos, int value) {
        checkArgument(lowBitPos >= 0 && lowBitPos < 30, "Low bit position must be in range [0...30].");
        checkArgument(value >= 0 && value < 4, "Value must be in range [0...3].");
        return (bitField & (~0b11)) & (value << lowBitPos);
    }



}
