package com.conveyal.r5.otp2.util.paretoset;

import org.junit.Test;

import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

public class ParetoSetWithMarkerTest {
    private ParetoSetWithMarker<Vector> subject = new ParetoSetWithMarker<>((l, r) -> l.u < r.u || l.v < r.v);

    @Test
    public void verifyMarkerIsInitializedToZero() {
        assertEquals("{}", toString(subject));
    }

    @Test
    public void verifyMarkerExistAfterElementsIsAdded() {
        subject.add(v(1,1));
        assertEquals("<M>, [1, 1]", toString(subject));
    }

    @Test
    public void verifyMarkerStaysAtBeginningOfSetWhenElementsAreAdded() {
        subject.add(v(5,5));
        assertEquals("<M>, [5, 5]", toString(subject));
        subject.add(v(3,3));
        assertEquals("<M>, [3, 3]", toString(subject));
        subject.add(v(1,5));
        assertEquals("<M>, [3, 3], [1, 5]", toString(subject));
        subject.add(v(1,4));
        assertEquals("<M>, [3, 3], [1, 4]", toString(subject));
    }

    @Test
    public void verifyMarkerStaysInRightPlaceWhenNewElementsAreAdded() {
        subject.add(v(5,5));

        subject.markAtEndOfSet();
        assertEquals("[5, 5], <M>", toString(subject));

        subject.add(v(8,8));
        assertEquals("[5, 5], <M>", toString(subject));

        subject.add(v(3,7));
        assertEquals("[5, 5], <M>, [3, 7]", toString(subject));

        subject.add(v(4,3));
        assertEquals("<M>, [3, 7], [4, 3]", toString(subject));

        subject.markAtEndOfSet();
        subject.add(v(2,4));
        assertEquals("[4, 3], <M>, [2, 4]", toString(subject));
    }

    @Test
    public void clear() {
        subject.clear();
        assertEquals("{}", toString(subject));

        // Add an element to make sure the marker is set to 0 when cleared (above)
        subject.add(v(5,5));
        assertEquals("<M>, [5, 5]", toString(subject));

        subject.markAtEndOfSet();
        assertEquals("[5, 5], <M>", toString(subject));

        // Clear and add an element to make sure the marker is set back to 0
        subject.clear();
        subject.add(v(5,5));
        assertEquals("<M>, [5, 5]", toString(subject));
    }

    @Test
    public void iteratorFromMark() {
        assertEquals(
                "Empty set have no elements after marker",
                "{}",
                toString(subject.streamAfterMarker())
        );

        subject.add(v(5,5));
        subject.markAtEndOfSet();
        assertEquals(
                "Still empty - no elements after marker",
                "{}",
                toString(subject.streamAfterMarker())
        );

        subject.markAtEndOfSet();
        subject.add(v(3,7));
        assertEquals(
                "Return one element after marker",
                "[3, 7]",
                toString(subject.streamAfterMarker())
        );
    }

    @Test
    public void verifyMultipleElementsAddedAfterMarker() {
        // Given an element before the mark
        subject.add(v(9,1));
        // When mark set
        subject.markAtEndOfSet();
        // And 3 elements added
        subject.add(v(6,4));
        subject.add(v(5,5));
        subject.add(v(4,6));

        // Then all 3 elements exist AFTER marker
        assertEquals("[9, 1], <M>, [6, 4], [5, 5], [4, 6]", toString(subject));
        assertEquals("[6, 4], [5, 5], [4, 6]", toString(subject.streamAfterMarker()));
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private String toString(ParetoSetWithMarker<Vector> set) {
        if(set.isEmpty()) {
            return "{}";
        }
        Vector firstVectorAfterMarker = set.streamAfterMarker().findFirst().orElse(null);
        if(firstVectorAfterMarker != null) {
            return toString(set.stream(), it -> it == firstVectorAfterMarker ? "<M>, " + it : it.toString());
        }
        return toString(set.stream()) + ", <M>";
    }

    private String toString(Stream<Vector> stream) {
        return toString(stream, Objects::toString);
    }

    private String toString(Stream<Vector> stream, Function<Vector, String> mapper) {
        return stream.map(mapper)
                .reduce((a,b) -> a + ", " + b)
                .orElse("{}");
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