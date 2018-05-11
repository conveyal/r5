package com.conveyal.r5.util;

/**
 * Tuple of two elements with same type
 */
public class P2<E> {
    public final E a;

    public final E b;

    /**
     * Creates a new pair
     *
     * @param b   The key for this pair
     * @param b The value to use for this pair
     */
    public P2(
        E a,
        E b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public String toString() {
        return String.format("P2<%s %s>", a, b);
    }

    @Override
    public int hashCode() {
        return (a != null ? a.hashCode() : 0) +
                (b != null ? b.hashCode() * 31 : 0);
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof P2)) return false;
        P2 other = (P2) o;
        boolean aIsEqual = (a == null) ? other.a == null : a.equals(other.a);
        boolean bIsEqual = (b == null) ? other.b == null : b.equals(other.b);
        return aIsEqual && bIsEqual;
    }
}
