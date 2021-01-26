package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.analyst.cluster.PathResult;
import gnu.trove.list.TIntList;

import java.util.Objects;

import static com.google.common.base.Preconditions.checkState;

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
    public StreetTimesAndModes.StreetTimeAndMode access;
    public StreetTimesAndModes.StreetTimeAndMode egress;

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
        StopSequence that = (StopSequence) o;
        return Objects.equals(boardStops, that.boardStops) &&
                Objects.equals(alightStops, that.alightStops) &&
                Objects.equals(rideTimesSeconds, that.rideTimesSeconds) &&
                Objects.equals(access, that.access) &&
                Objects.equals(egress, that.egress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boardStops, alightStops, rideTimesSeconds, access, egress);
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
     * Set the time spent transferring between stops, which is not stored in our Raptor implementation but can be
     * calculated by  subtracting the other components of travel time from the total travel time
     */
    public int transferTime(PathResult.Iteration iteration) {
        if (access == null && egress == null && iteration.waitTimes.size() == 0 && rideTimesSeconds == null) {
            // No transit ridden, so transfer time is 0.
            return 0;
        } else {
            int transferTimeSeconds =
                    iteration.totalTime - access.time - egress.time - iteration.waitTimes.sum() - rideTimesSeconds.sum();
            checkState(transferTimeSeconds >= 0);
            return transferTimeSeconds;
        }
    }

}
