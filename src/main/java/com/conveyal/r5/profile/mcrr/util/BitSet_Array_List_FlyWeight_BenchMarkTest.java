package com.conveyal.r5.profile.mcrr.util;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class BitSet_Array_List_FlyWeight_BenchMarkTest {

    private static final AvgTimer TIMER_BIT_SET = timer("BitSet (no times)");
    private static final AvgTimer TIMER_ARRAY = timer("Array");
    private static final AvgTimer TIMER_ARRAY_LIST = timer("ArrayList");
    private static final AvgTimer TIMER_FW = timer("FlyWeight");
    private static final AvgTimer TIMER_FW2 = timer("FlyWeight_2");

    private static final int TEST_ITTERATIONS = 1_000;
    private static final int TUTCHED_ITEMS = 10_000;
    private static final int TOT_NUM_OF_ITEMS = 1_000_000;

    private static final BitSet bitSet = new BitSet(TOT_NUM_OF_ITEMS);
    private static final int[] arrayStops = new int[TOT_NUM_OF_ITEMS];
    private static final int[] arrayTimes = new int[TOT_NUM_OF_ITEMS];
    private static final List<Pair> arrayList = new ArrayList<>(TOT_NUM_OF_ITEMS);
    private static final  PairFW flyWeight = new PairFW(TOT_NUM_OF_ITEMS);
    private static final  PairFW2 flyWeight_2 = new PairFW2(TOT_NUM_OF_ITEMS);



    public static void main(String[] args) {
        Random rnd = new Random(2344);
        int[] cases = new int[TUTCHED_ITEMS];
        Set<Integer> uniqeTestCases = new HashSet<>();

        int i = 0;
        while (uniqeTestCases.size() < TUTCHED_ITEMS) {
            int v = rnd.nextInt(TOT_NUM_OF_ITEMS);
            if (uniqeTestCases.add(v)) {
                cases[i] = v;
                ++i;
            }
        }
        long sum = 0;
        for(i=0; i<TEST_ITTERATIONS; ++i) {
            sum += testBitSet(cases);
            sum += testArray(cases);
            sum += testArrayList(cases);
            sum += testFlyWeight(cases);
            sum += testFlyWeight2(cases);
        }
        System.out.println(sum);
        AvgTimer.listResults().forEach(System.out::println);
    }


    private static AvgTimer timer(String name) {
        return AvgTimer.timerMicroSec(name);
    }


    private static long testBitSet(int[] cases) {
        int i;
        TIMER_BIT_SET.start();
        bitSet.clear();

        for (i = 0; i < cases.length; i++) {
            bitSet.set(cases[i]);
        }
        long sum = 0;

        for (int v = bitSet.nextSetBit(0); v != -1; v = bitSet.nextSetBit(v + 1)) {
            sum += v;
        }
        TIMER_BIT_SET.stop();

        return sum;

    }

    private static long testArray(int[] cases) {
        int i;
        TIMER_ARRAY.start();
        //Arrays.fill(array, -1);

        for (i = 0; i < cases.length; i++) {
            arrayStops[i] = cases[i];
            arrayTimes[i] = i;
        }
        long sum = 0;

        for (int k = 0; k < i; ++k) {
            sum += arrayStops[k] + arrayTimes[k];
        }
        TIMER_ARRAY.stop();

        return sum;
    }


    private static long testArrayList(int[] cases) {
        int i;
        TIMER_ARRAY_LIST.start();
        arrayList.clear();

        for (i = 0; i < cases.length; i++) {
            arrayList.add(new Pair(cases[i], i));
        }
        long sum = 0;

        for (Pair it : arrayList) {
            sum += it.stop + it.time;
        }

        TIMER_ARRAY_LIST.stop();

        return sum;
    }


    private static long testFlyWeight(int[] cases) {
        int i;

        TIMER_FW.start();
        flyWeight.clear();

        for (i = 0; i < cases.length; i++) {
            flyWeight.add(cases[i], i);
        }
        long sum = 0;

        flyWeight.switchToReadMode();

        while(flyWeight.hasMore()) {
            sum += flyWeight.stop() + flyWeight.time();
            flyWeight.next();
        }

        TIMER_FW.stop();

        return sum;
    }


    private static long testFlyWeight2(int[] cases) {
        int i;

        TIMER_FW2.start();
        flyWeight_2.clear();

        for (i = 0; i < cases.length; i++) {
            flyWeight_2.add(cases[i], i);
        }
        long sum = 0;

        flyWeight_2.switchToReadMode();

        while(flyWeight_2.hasMore()) {
            sum += flyWeight_2.stop() + flyWeight_2.time();
            flyWeight_2.next();
        }

        TIMER_FW2.stop();

        return sum;
    }

    final static class Pair {
        int stop;
        int time;

        Pair(int stop, int time) {
            this.stop = stop;
            this.time = time;
        }
    }

    final static class PairFW {
        private final int[] v;
        private int size, i = 0;


        PairFW(int size) {
            this.v = new int[size*2];
        }

        public void clear() {
            i = 0;
            size = 0;
        }
        void add(int stop, int time) {
            v[i] = stop;
            ++i;
            v[i] = time;
            ++i;
        }
        void switchToReadMode() {
            size = i;
            i = 0;
        }
        final int stop() { return v[i]; }
        final int time() { return v[i+1]; }
        final void next() { i += 2; }
        final boolean hasMore() { return i < size; }

    }

    final static class PairFW2 {
        private final int[] stops;
        private final int[] times;
        private int size, i = 0;


        PairFW2(int size) {
            this.stops = new int[size];
            this.times = new int[size];
        }

        public void clear() {
            i = 0;
            size = 0;
        }

        void add(int stop, int time) {
            stops[i] = stop;
            times[i] = time;
            ++i;
        }
        void switchToReadMode() {
            size = i;
            i = 0;
        }
        final int stop() { return stops[i]; }
        final int time() { return times[i]; }
        final void next() { ++i; }
        final boolean hasMore() { return i < size; }
    }
}
