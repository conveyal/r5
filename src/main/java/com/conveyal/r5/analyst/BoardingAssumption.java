package com.conveyal.r5.analyst;

/**
 * The assumptions made when boarding a frequency vehicle: best case (no wait), worst case (full headway)
 * and half headway (in some sense the average).
 */
public enum BoardingAssumption {
    BEST_CASE, WORST_CASE, HALF_HEADWAY, FIXED, PROPORTION, RANDOM;
    public static final long serialVersionUID = 1;
}