package com.conveyal.r5.common;

/**
 * Created by abyrd on 2017-11-29
 */
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

}
