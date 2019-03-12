package com.conveyal.r5.profile.entur.util;

import java.util.function.IntFunction;


/**
 * The responsibility of this class is to compare two int arrays and list all elements
 * that differ. You may provide a list of indexes to compare or compare all elements.
 * <p/>
 * The result is returned as a multi-line string.
 * <p/>
 * If the header line exceeds 2000 characters the comparison is aborted.
 * <p/>
 * Both regular numbers and time is supported. The time uses the {@link TimeUtils#timeToStrCompact(int)}
 * to print all times.
 */
public class CompareIntArrays {

    public static String compareTime(
            String label, String aName, int[] a, String bName, int[] b, int unreached, int[] stops
    ) {
        return compare(label, aName, a, bName, b, TimeUtils::timeToStrCompact, unreached, stops);
    }

    public static String compare(
            String label, String aName, int[] a, String bName, int[] b, int unreached, int[] stops
    ) {
        return compare(label,aName, a, bName, b, Integer::toString, unreached, stops);
    }

    private static String compare(
            String label,
            String aName,
            int[] a,
            String bName,
            int[] b,
            IntFunction<String> mapValue,
            int unreached,
            int[] stops
    ) {
        State s = new State(label, aName, bName, unreached, mapValue);

        if(stops != null) {
            s.compare(a, b, stops);
        }
        else {
            s.compareAll(a, b);
        }
        return s.result();
    }

    static class State {
        private final String label;
        private final String aName;
        private final String bName;
        private final int unreached;
        private final IntFunction<String> mapValue;

        private String hh = "";
        private String r1 = "";
        private String r2 = "";

        State(String label, String aName, String bName, int unreached, IntFunction<String> mapValue) {
            this.label = label;
            this.aName = aName;
            this.bName = bName;
            this.unreached = unreached;
            this.mapValue = mapValue;
        }

        void compare(int[] a, int[] b, int[] index) {
            for (int i : index) {
                compare(i, a[i], b[i]);
            }
        }

        void compareAll(int[] a, int[] b) {
            for (int i = 0; i < a.length; ++i) {
                compare(i, a[i], b[i]);
            }
        }

        void compare(int i, int v, int u) {
            if(u != v) {
                if(hh.length() > 2000) {
                    return;
                }
                hh += String.format("%8d ", i);
                r1 += String.format("%8s ", toStr(u));
                r2 += String.format("%8s ", toStr(v));
            }
        }

        String toStr(int v) {
            return v == unreached ? "" : mapValue.apply(v);
        }

        String result() {
            int w = Math.max(4, Math.max(aName.length(), bName.length()));
            String f = "%-" + w + "s  %s%n";
            String result = label + '\n';
            result += String.format(f, "Stop", hh);
            result += String.format(f, aName, r1);
            result += String.format(f, bName, r2);
            return result;
        }
    }
}
