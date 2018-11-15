package com.conveyal.r5.diff;

/**
 * This represents a single difference found between two objects by the ObjectDiffer.
 */
public class Difference {

    Object a;
    Object b;
    String message;

    public Difference(Object a, Object b) {
        this.a = a;
        this.b = b;
    }

    public Difference withMessage (String formatString, Object... args) {
        message = String.format(formatString, args);
        return this;
    }

}
