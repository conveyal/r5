package com.conveyal.r5.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

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

    public static boolean isNullOrEmpty (Map map) {
        return map == null || map.isEmpty();
    }

    public static boolean notNullOrEmpty (Map map) {
        return !isNullOrEmpty(map);
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
     * Convenience method to create a 2D array and fill it immediately with new instances of a class.
     * The supplier can be a method reference to a constructor like ToBeInstantiated::new, and the returned
     * array will be of that type.
     */
    public static <T> T[][] newObjectArray (int d1, int d2, Supplier<T> supplier) {
        T[][] result = (T[][]) new Object[d1][d2];
        for (int x = 0; x < d1; x++) {
            for (int y = 0; y < d2; y++) {
                result[x][y] = supplier.get();
            }
        }
        return result;
    }

}
