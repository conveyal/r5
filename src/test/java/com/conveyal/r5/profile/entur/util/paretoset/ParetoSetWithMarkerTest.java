package com.conveyal.r5.profile.entur.util.paretoset;

import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ParetoSetWithMarkerTest {
    private ParetoSetWithMarker<Vector> subject = new ParetoSetWithMarker<>((l,r) -> l.u < r.u || l.v < r.v);

    @Test
    public void verifyMarkerIsInitializedToZero() {
        assertEquals(0, subject.marker());
    }

    @Test
    public void verifyMarkerStaysAtBeginningOfSetWhenElementsAreAdded() {
        subject.add(v(5,5));
        assertEquals(0, subject.marker());
        subject.add(v(3,3));
        assertEquals(0, subject.marker());
        subject.add(v(1,5));
        assertEquals(0, subject.marker());
        subject.add(v(1,4));
        assertEquals(0, subject.marker());
    }

    @Test
    public void verifyMarkerStaysInRightPlaceWhenNewElementsAreAdded() {
        subject.add(v(5,5));

        subject.markEndOfSet();
        assertEquals(1, subject.marker());

        subject.add(v(8,8));
        assertEquals("{[5, 5]}", subject.toString());
        assertEquals(1, subject.marker());

        subject.add(v(3,7));
        assertEquals("{[5, 5], [3, 7]}", subject.toString());
        assertEquals(1, subject.marker());

        subject.add(v(4,3));
        assertEquals("{[3, 7], [4, 3]}", subject.toString());
        assertEquals(0, subject.marker());

        subject.markEndOfSet();
        subject.add(v(2,4));
        assertEquals("{[4, 3], [2, 4]}", subject.toString());
        assertEquals(1, subject.marker());
    }

    @Test
    public void clear() {
        subject.clear();
        assertEquals(0, subject.marker());

        subject.add(v(5,5));
        subject.markEndOfSet();
        assertEquals(1, subject.marker());
        subject.clear();
        assertEquals(0, subject.marker());
    }

    @Test
    public void iteratorFromMark() {
        assertFalse("Empty set have no elements after marker", subject.listFromMark().hasNext());

        subject.add(v(5,5));
        subject.markEndOfSet();
        assertFalse("Still empty - no elements after marker", subject.listFromMark().hasNext());

        subject.markEndOfSet();
        subject.add(v(3,7));
        assertEquals("Return one element after marker", "[3, 7]", toString(subject.listFromMark()));
    }

    @Test
    public void verifyListWith3ElementslistFromMark() {
        // Given an element before the mark
        subject.add(v(9,1));
        // When mark set
        subject.markEndOfSet();
        // And 3 elements added
        subject.add(v(6,4));
        subject.add(v(5,5));
        subject.add(v(4,6));

        // Then all 3 elements exist
        assertEquals("[6, 4], [5, 5], [4, 6]", toString(subject.listFromMark()));
        // and only one element has 5 as its last criteria
        assertEquals("[5, 5]", toString(subject.listFromMark(it -> it.v == 5)));
    }

    private String toString(Iterator<Vector> list) {
        if(!list.hasNext()) {
            return "";
        }
        StringBuilder tmp = new StringBuilder(list.next().toString());
        while (list.hasNext()) {
            tmp.append(", ").append(list.next());
        }
        return tmp.toString();
    }

    static private Vector v(int u, int v) {
        return new Vector(u, v);
    }

    private static class Vector {
        final int u, v;
        Vector(int u, int v) {
            this.u = u;
            this.v = v;
        }

        @Override
        public String toString() {
            return "[" + u + ", " + v + "]";
        }
    }
}