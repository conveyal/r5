package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import gnu.trove.list.TIntList;

import java.util.Objects;

import static com.google.common.base.Preconditions.checkState;

/**
 * A door-to-door path, i.e. access/egress characteristics and transit legs (keyed on characteristics including per-leg
 * in-vehicle times but not specific trips/patterns/routes), which may be repeated at different departure times.
 *
 * Instances are constructed initially from transit legs, with access, egress, and transferTimes set in successive
 * operations.
 */
public class StopSequence {
    public final TIntList boardStops;
    public final TIntList alightStops;
    public final TIntList rideTimesSeconds;
    public StreetTimesAndModes.StreetTimeAndMode access;
    public StreetTimesAndModes.StreetTimeAndMode egress;
    // This could be calculated from other fields, but we explicitly calculate it for convenience
    public int transferTimeSeconds;

    /**
     * Populate the basic transit path characteristics
     */
    StopSequence(TIntList boardStops, TIntList alightStops, TIntList rideTimesSeconds) {
        this.boardStops = boardStops;
        this.alightStops = alightStops;
        this.rideTimesSeconds = rideTimesSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StopSequence path = (StopSequence) o;
        return  boardStops.equals(path.boardStops) &&
                alightStops.equals(path.alightStops) &&
                rideTimesSeconds.equals(path.rideTimesSeconds) &&
                this.access.equals(path.access) &&
                this.egress.equals(path.egress) &&
                this.transferTimeSeconds == path.transferTimeSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(access, egress, transferTimeSeconds, boardStops, alightStops, rideTimesSeconds);
    }

    /**
     * Set access to the first boarding stop,
     * @param bestAccessOptions map with the optimal access time/mode to reach each stop in the network
     */
    public void setAccess(StreetTimesAndModes bestAccessOptions) {
        access = bestAccessOptions.streetTimesAndModes.get(boardStops.get(0));
    }

    public void setEgress(StreetTimesAndModes.StreetTimeAndMode egress) {
        this.egress = egress;
    }

    /**
     * Set the time spent transfering between stops, which is not stored in our Raptor implementation but can be
     * calculated by  subtracting the other components of travel time from the total travel time
     */
    public void setTransferTime(int totalTime, TIntList waitTimes) {
        transferTimeSeconds = totalTime - access.time - egress.time - waitTimes.sum() - rideTimesSeconds.sum();
        checkState(transferTimeSeconds >= 0);
    }

}
