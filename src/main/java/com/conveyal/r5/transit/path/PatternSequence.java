package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import gnu.trove.list.TIntList;

import java.util.Objects;

/**
 * A door-to-door path made up of transit legs, each composed of a TripPattern, board and alight stop.
 * There is also information on ride and wait times and access and egress modes.
 */
public class PatternSequence {

    /** Pattern indexes (those used in R5 TransitLayer) for each transit leg in the itinerary. */
    public final TIntList patterns;

    /** Other information associated with each of the legs: board and alight stops, ride times etc. */
    public final StopSequence stopSequence;

    /**
     * Create a PatternSequence from transit leg characteristics.
     */
    public PatternSequence(TIntList patterns, TIntList boardStops, TIntList alightStops, TIntList rideTimesSeconds) {
        this.patterns = patterns;
        this.stopSequence = new StopSequence(boardStops, alightStops, rideTimesSeconds);
    }

    /**
     * Given a source PatternSequence, shallow copy its patterns, and the fields of its stopSequence except for the
     * egress, which is set to the supplied egress.
     */
    public PatternSequence(PatternSequence source, StreetTimesAndModes.StreetTimeAndMode egress) {
        this.patterns = source.patterns;
        StopSequence sequence = source.stopSequence;
        this.stopSequence = new StopSequence(sequence.boardStops, sequence.alightStops, sequence.rideTimesSeconds);
        this.stopSequence.access = source.stopSequence.access;
        this.stopSequence.egress = egress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PatternSequence that = (PatternSequence) o;
        return Objects.equals(patterns, that.patterns) &&
                Objects.equals(stopSequence, that.stopSequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patterns, stopSequence);
    }

}
