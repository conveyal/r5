package com.conveyal.r5.util;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.conveyal.r5.util.ParetoDominateFunction.createParetoDominanceFunctionArray;
import static java.util.Collections.singletonList;
import static org.junit.Assert.*;

public class ParetoSetTest {

    private ParetoSet<MyClass> set;
    private final MyClass initialValue_555 = new MyClass(5, 5, 5);
    // And a inital vector [5,5,5]
    private final List<MyClass> initialSet_555 = singletonList(initialValue_555);

    @Before
    public void setUp() {
        // Given a 3 dimentional ParetoSet where [LESS IS BEST, GREAT IS BEST, DIFFERENT IS BEST]
        set = new ParetoSet<>(
                createParetoDominanceFunctionArray()
                        .lessThen()
                        .greaterThen()
                        .different()
        );
        set.add(initialValue_555);

        // Given initial base line
        assertEquals(initialSet_555, set.paretoSet());
    }

    @Test
    public void addValuesThatIsDominatedByTheInitialSet() {
        // Add a new vector where first value 4 disqualifies it
        set.add(new MyClass(5, 4, 5));
        assertEquals(initialSet_555, set.paretoSet());

        // Add a new vector where second value 4 disqualifies it
        set.add(new MyClass(5, 4, 5));
        assertEquals(initialSet_555, set.paretoSet());

        // Add a new vector where first value is to big, second value is to small and last value is the same
        set.add(new MyClass(6, 4, 5));
        assertEquals(initialSet_555, set.paretoSet());
    }

    @Test
    public void firstValueDominatesExistingValue_4_is_LessThen_5() {
        set.add(new MyClass(4, 5, 5));
        assertEquals("[[4, 5, 5]]", set.paretoSet().toString());
    }

    @Test
    public void secondValueDominatesExistingValue_6_is_GreatherThen_5() {
        set.add(new MyClass(5, 6, 5));
        assertEquals("[[5, 6, 5]]", set.paretoSet().toString());
    }

    @Test
    public void thirdValueDominatesEachOther_3_is_diffrent_from_5() {
        set.add(new MyClass(5, 5, 3));
        assertEquals("[[5, 5, 5], [5, 5, 3]]", set.paretoSet().toString());
    }

    @Test
    public void addValuesToParetoSetThatIsEquallyAsGoodAsTheValuesInTheSetAndVerifyThatSetIsGrowing() {
        // Given initial base line
        assertEquals(initialSet_555, set.paretoSet());

        set.add(new MyClass(4, 4, 5));
        assertEquals("[[5, 5, 5], [4, 4, 5]]", set.paretoSet().toString());

        set.add(new MyClass(6, 6, 5));
        assertEquals("[[5, 5, 5], [4, 4, 5], [6, 6, 5]]", set.paretoSet().toString());

        set.add(new MyClass(5, 5, 6));
        assertEquals("[[5, 5, 5], [4, 4, 5], [6, 6, 5], [5, 5, 6]]", set.paretoSet().toString());
    }

    private static class MyClass implements ParetoSortable {
        int[] values;

        MyClass(int a, int b, int c) {
            values = new int[3];
            values[0] = a;
            values[1] = b;
            values[2] = c;
        }

        @Override
        public int[] paretoValues() {
            return values;
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }
}