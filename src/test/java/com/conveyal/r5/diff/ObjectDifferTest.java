package com.conveyal.r5.diff;

import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * This test verifies that the ObjectDiffer can find differences in nested objects.
 * An ObjectDiffer is expected to automatically perform a recursive, semantic comparison of the two supplied objects.
 * TODO add tests for object graphs with cycles.
 */
public class ObjectDifferTest {

    /**
     * Create two semantically equal object trees that are composed of completely separate instances.
     * No differences should be found between the two until we make a change to one of them.
     */
    @Test
    public void testContrivedNesting() {

        // First make the two separate but semantically identical object trees.
        Map<Integer, Fields> o1 = makeIntegerFieldsMap();
        Map<Integer, Fields> o2 = makeIntegerFieldsMap();
        ObjectDiffer objectDiffer = new ObjectDiffer();
        objectDiffer.compareTwoObjects(o1, o2);
        assertFalse(objectDiffer.hasDifferences());

        // Now make one of the two nested objects different. Differences should be detected.
        o2.get(10).nestedMap.get("50").clear();
        objectDiffer = new ObjectDiffer(); // TODO add a reset function?
        objectDiffer.compareTwoObjects(o1, o2);
        objectDiffer.printDifferences();
        assertTrue(objectDiffer.hasDifferences());

    }

    /**
     * Create two semantically equal object trees that are composed of completely separate instances of Trove maps.
     * No differences should be found between the two until we make a change to one of them.
     */
    @Test
    public void testCompareTroveObjectTree () {

        // First make the two separate but semantically identical object trees.
        TIntObjectMap<TLongObjectMap<TIntIntMap>> o1 = makeDoublyNestedTroveMap();
        TIntObjectMap<TLongObjectMap<TIntIntMap>> o2 = makeDoublyNestedTroveMap();
        ObjectDiffer objectDiffer = new ObjectDiffer();
        objectDiffer.compareTwoObjects(o1, o2);
        objectDiffer.printDifferences();
        assertFalse(objectDiffer.hasDifferences());

        // Now make one of the two nested objects different. Differences should be detected.
        o2.get(50).get(5_500_000).remove(74);
        objectDiffer = new ObjectDiffer();
        objectDiffer.compareTwoObjects(o1, o2);
        objectDiffer.printDifferences();
        assertTrue(objectDiffer.hasDifferences());

    }

    /* Helper functions to build a complicated tree of objects. */

    private Map<Integer, Fields> makeIntegerFieldsMap() {
        Map<Integer, Fields> result = new HashMap<>();
        for (int i = 0; i < 100; i += 10) {
            result.put(Integer.valueOf(i), new Fields(i));
        }
        return result;
    }

    private static class Fields {
        int integer;
        String string;
        Double doubleObject;
        Map<String, Map<String, int[]>> nestedMap;

        public Fields(int i) {
            this.integer = i;
            this.string = Integer.toString(i);
            this.doubleObject = Double.valueOf(i);
            this.nestedMap = makeNestedMap();
        }
    }

    public static Map<String, Map<String, int[]>> makeNestedMap() {
        Map<String, Map<String, int[]>> result = new HashMap<>();
        for (int x = 10; x < 100; x += 10) {
            result.put(Integer.toString(x), makeStringIntMap(x, 10));
        }
        return result;
    }

    public static Map<String, int[]> makeStringIntMap(int start, int size) {
        Map<String, int[]> result = new HashMap<>();
        for (int x = start; x < start + size; x++) {
            int[] array = makeSequentialArray(x, 10);
            String string = Integer.toString(x);
            result.put(string, array);
        }
        return result;
    }

    public static int[] makeSequentialArray(int start, int length) {
        return IntStream.rangeClosed(start, start + length).toArray();
    }

    /* Helper functions to build a complicated tree of Trove maps. */

    private static TIntObjectMap<TLongObjectMap<TIntIntMap>> makeDoublyNestedTroveMap() {
        TIntObjectMap<TLongObjectMap<TIntIntMap>> result = new TIntObjectHashMap();
        for (int i = 0; i < 1000; i += 10) {
            result.put(i, makeNestedTroveMap(i));
        }
        return result;
    }

    private static TLongObjectMap<TIntIntMap> makeNestedTroveMap(int start) {
        TLongObjectMap<TIntIntMap> result = new TLongObjectHashMap<>();
        for (int i = start; i < start + 20; i++) {
            result.put(i * 100_000L, makeTroveIntMap(i));
        }
        return result;
    }

    private static TIntIntMap makeTroveIntMap(int start) {
        TIntIntMap result = new TIntIntHashMap();
        for (int i = start; i < start + 20; i++) {
            result.put(i, i + 10);
        }
        return result;
    }

}