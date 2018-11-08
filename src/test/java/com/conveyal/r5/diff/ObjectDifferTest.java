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
    public void testCompareNestedObjectTree () {

        // First make the two separate but semantically identical object trees.
        Map<Integer, Fields> o1 = makeIntegerFieldsMap();
        Map<Integer, Fields> o2 = makeIntegerFieldsMap();
        assertDifferences(false, o1, o2);

        // Now make one of the two nested objects different. Differences should be detected.
        o2.get(10).nestedMap.get("50").clear();
        assertDifferences(true, o1, o2);

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
        assertDifferences(false, o1, o2);

        // Now make one of the two nested objects different. Differences should be detected.
        o2.get(50).get(5_500_000).remove(74);
        assertDifferences(true, o1, o2);

    }

    /**
     * This verifies that the map comparison will detect the case where the mappings in a are a subset
     * of those in b or vice versa.
     */
    @Test
    public void testSubsetNotEqual () {

        // First make the two separate but semantically identical object trees.
        TIntIntMap m1 = makeTroveIntMap(10);
        TIntIntMap m2 = makeTroveIntMap(10);
        assertDifferences(false, m1, m2);

        // Now make one of the two nested objects different. Differences should be detected.
        m1.remove(15);
        assertDifferences(true, m1, m2);

    }

    /**
     * Check that mismatched no-entry values are caught.
     */
    @Test
    public void testNoEntryValue () {
        TIntIntMap a = makeTroveIntMap(10);
        TIntIntMap b = new TIntIntHashMap(100, 0.4f, -1, -2);
        TIntIntMap c = new TIntIntHashMap(200, 0.4f, -1, -2);
        TIntIntMap d = new TIntIntHashMap(100, 0.4f, -8, -9);
        b.putAll(a);
        c.putAll(a);
        d.putAll(a);
        assertDifferences(false, b, c);
        assertDifferences(true, b, d);
        assertDifferences(true, c, d);
    }

    /**
     * Check that the comparison methods are not sensitive to the underlying array sizes in maps by setting
     * a different initial capacity.
     */
    @Test
    public void testBackingArraySize () {
        TIntIntMap a = makeTroveIntMap(10);
        TIntIntMap b = new TIntIntHashMap(10, 0.2f);
        TIntIntMap c = new TIntIntHashMap(500, 0.4f);
        TIntIntMap d = new TIntIntHashMap(1000,  0.6f);
        b.putAll(a);
        c.putAll(a);
        d.putAll(a);
        // All three maps now have the same contents, but different backing array sizes.
        assertDifferences(false, a, b);
        assertDifferences(false, a, c);
        assertDifferences(false, a, d);
        assertDifferences(false, b, c);
        assertDifferences(false, b, d);
        assertDifferences(false, c, d);
    }

    /**
     * Utility method to create an ObjectDiffer, compare two objects, and assert that they are or are not equal.
     * This also checks that the difference is symmetric by repeating the comparison with the operands reversed.
     */
    private static void assertDifferences (boolean expectDifferences, Object a, Object b) {
        assertDifferences0(expectDifferences, a, b);
        assertDifferences0(expectDifferences, b, a);
    }

    private static void assertDifferences0 (boolean expectDifferences, Object a, Object b) {
        ObjectDiffer objectDiffer = new ObjectDiffer();
        objectDiffer.compareTwoObjects(a, b);
        objectDiffer.printDifferences();
        if (expectDifferences) {
            assertTrue(objectDiffer.hasDifferences());
        } else {
            assertFalse(objectDiffer.hasDifferences());
        }
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