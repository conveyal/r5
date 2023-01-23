package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import gnu.trove.list.TIntList;

import java.util.Objects;

/**
 * A door-to-door path that includes the patterns ridden between stops
 */
public class PatternSequence {
    /**
     * Pattern indexes (those used in R5 transit layer) for each transit leg
     */
    public final TIntList patternIndexes;
    public final StopSequence stopSequence;

    public final StreetTimeAndMode access;

    /**
     * Create a PatternSequence from transit leg characteristics.
     */
    public PatternSequence(TIntList patternIndexes, StopSequence stopSequence, StreetTimeAndMode access) {
        this.patternIndexes = patternIndexes;
        this.stopSequence = stopSequence;
        this.access = access;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PatternSequence that = (PatternSequence) o;
        return Objects.equals(patternIndexes, that.patternIndexes) &&
                Objects.equals(stopSequence, that.stopSequence) &&
                Objects.equals(access, that.access);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patternIndexes, stopSequence, access);
    }
}