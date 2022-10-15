package com.conveyal.r5.transit.path;

import gnu.trove.list.TIntList;

import java.util.Objects;

/**
 * A door-to-door path, i.e. access/egress characteristics and transit legs (keyed on characteristics including per-leg
 * in-vehicle times but not specific trips/patterns/routes), which may be repeated at different departure times.
 *
 * Instances are constructed initially from transit legs, with access and egress set in successive operations.
 */
public class StopSequence {
    public final TIntList boardStops;
    public final TIntList alightStops;
    public final TIntList rideTimesSeconds;

    /**
     * Populate the basic transit path characteristics
     */
    public StopSequence(TIntList boardStops, TIntList alightStops, TIntList rideTimesSeconds) {
        this.boardStops = boardStops;
        this.alightStops = alightStops;
        this.rideTimesSeconds = rideTimesSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StopSequence that = (StopSequence) o;
        return Objects.equals(boardStops, that.boardStops) &&
                Objects.equals(alightStops, that.alightStops) &&
                Objects.equals(rideTimesSeconds, that.rideTimesSeconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boardStops, alightStops, rideTimesSeconds);
    }
}
