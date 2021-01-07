package com.conveyal.r5.transit.path;

import gnu.trove.list.TIntList;

import java.util.Objects;

/**
 * A door-to-door path that includes the patterns ridden between stops
 */
public class PatternSequence {
    /** Pattern indexes (those used in R5 transit layer) for each transit leg */
    public final TIntList patterns;
    public StopSequence stopSequence;

    /**
     * Create a PatternSequence from transit leg characteristics.
     */
    PatternSequence(TIntList patterns, TIntList boardStops, TIntList alightStops, TIntList rideTimesSeconds) {
        this.patterns = patterns;
        this.stopSequence = new StopSequence(boardStops, alightStops, rideTimesSeconds);
    }

    /**
     * Copy a PatternSequence.
     */
    public PatternSequence(PatternSequence source) {
        this.patterns = source.patterns;
        this.stopSequence = source.stopSequence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PatternSequence path = (PatternSequence) o;
        return patterns.equals(path.patterns) &&
               this.stopSequence.equals(path.stopSequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patterns, stopSequence);
    }

}
