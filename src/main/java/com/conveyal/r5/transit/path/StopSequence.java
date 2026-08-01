package com.conveyal.r5.transit.path;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import gnu.trove.list.TIntList;

import java.util.Objects;

/// A door-to-door path, i.e. access/egress characteristics and transit legs (keyed on characteristics including per-leg
/// in-vehicle times but not specific trips/patterns/routes), which may be repeated at different departure times.
/// Instances are constructed initially from transit legs, with access and egress set in successive operations.
public class StopSequence {
    public final TIntList boardStops;
    public final TIntList alightStops;
    public final TIntList rideTimesSeconds;

    /// The time spent on the street network transferring to each transit leg. This is zero for the
    /// first leg, which is preceded by the access leg rather than a transfer. These are fully
    /// determined by the stops boarded and alighted, so excluded from equality checks and hash codes.
    public final TIntList transferTimesSeconds;
    public StreetTimesAndModes.StreetTimeAndMode access;
    public StreetTimesAndModes.StreetTimeAndMode egress;

    /// Populate the basic transit path characteristics
    StopSequence(TIntList boardStops, TIntList alightStops, TIntList rideTimesSeconds, TIntList transferTimesSeconds) {
        this.boardStops = boardStops;
        this.alightStops = alightStops;
        this.rideTimesSeconds = rideTimesSeconds;
        this.transferTimesSeconds = transferTimesSeconds;
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

    /// Set access to the first boarding stop,
    /// @param bestAccessOptions map with the optimal access time/mode to reach each stop in the network
    public void setAccess(StreetTimesAndModes bestAccessOptions) {
        access = bestAccessOptions.streetTimesAndModes.get(boardStops.get(0));
    }

    public void setEgress(StreetTimesAndModes.StreetTimeAndMode egress) {
        this.egress = egress;
    }

    /// Return the total time spent transferring between stops over all legs of this StopSequence,
    /// as recorded when the path was reconstructed from the router's internal state. Unlike
    /// waiting times, transfer times do not vary with departure time or randomized schedules.
    public int totalTransferTimeSeconds() {
        // Paths that ride no transit have no stop lists at all, and therefore no transfers.
        return (transferTimesSeconds == null) ? 0 : transferTimesSeconds.sum();
    }

}
