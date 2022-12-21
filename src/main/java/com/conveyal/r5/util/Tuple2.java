package com.conveyal.r5.util;

import java.util.Objects;

/**
 * Generic logic for a 2-tuple of different types.
 * Reduces high-maintenance boilerplate clutter when making map key types.
 * TODO replace with Records in Java 16 or 17
 */
public class Tuple2<A, B> {
    public final A a;
    public final B b;

    public Tuple2 (A a, B b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tuple2<?, ?> tuple2 = (Tuple2<?, ?>) o;
        return Objects.equals(a, tuple2.a) && Objects.equals(b, tuple2.b);
    }

    @Override
    public int hashCode () {
        return Objects.hash(a, b);
    }
}
