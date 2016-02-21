package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.Lists;
import com.google.common.primitives.Booleans;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Skip stops and associated dwell times.
 *
 * Skipped stops are no longer served by the matched trips, and and dwell time at a skipped stop is removed from the schedule.
 * If stops are skipped at the start of a trip, the start of the trip is simply removed; the remaining times are not shifted.
 */
public class SkipStop extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(SkipStop.class);

    /** On which route the stops should be skipped. */
    public String routeId;

    /** Stops to skip. Note that setting this to null as a wildcard is not supported, obviously. */
    public Collection<String> stopId;

    /** Internal integer IDs for a specific transit network, converted from the string IDs. */
    private transient TIntSet stopsToRemove;

    @Override
    public String getType() {
        return "skip-stop";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        return false;
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        boolean foundErrors = false;
        stopsToRemove = new TIntHashSet();
        for (String stringStopId : stopId) {
            int intStopId = network.transitLayer.indexForStopId.get(stringStopId);
            if (intStopId == 0) { // FIXME should be -1 not 0
                warnings.add("Could not find a stop with GTFS ID " + stringStopId);
                foundErrors = true;
            } else {
                stopsToRemove.add(intStopId);
            }
        }
        LOG.debug("Resolved stop IDs for removal. Strings {} resolved to integers {}.", stopId, stopsToRemove);
        // For debugging.
//        Set<String> names = new HashSet<>();
//        stopsToRemove.forEach(intStopId -> {
//            names.add(network.transitLayer.stopNames.get(intStopId));
//            return true; // Continue iteration.
//        });
//        names.stream().forEach(name -> {
//            LOG.info("Stop selected for removal: {}", name);
//        });
        return foundErrors;
    }

    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        // TODO filter by trip IDs too
        if (!routeId.contains(originalTripPattern.routeId)) {
            return originalTripPattern;
        }
        int nToRemove = (int) Arrays.stream(originalTripPattern.stops).filter(stopsToRemove::contains).count();
        if (nToRemove == 0) {
            return originalTripPattern;
        }
        LOG.debug("Modifying {}", originalTripPattern);
        // Make a protective copy that we can destructively modify.
        TripPattern pattern = originalTripPattern.clone();
        int oldLength = originalTripPattern.stops.length;
        int newLength = oldLength - nToRemove;
        pattern.stops = new int[newLength];
        pattern.pickups = new PickDropType[newLength];
        pattern.dropoffs = new PickDropType[newLength];
        pattern.wheelchairAccessible = new BitSet(newLength);
        // First, copy pattern-wide information for the stops to be retained, and record which stops are to be removed.
        boolean removeStop[] = new boolean[oldLength];
        for (int i = 0, j = 0; i < oldLength; i++) {
            if (stopsToRemove.contains(originalTripPattern.stops[i])) {
                removeStop[i] = true;
            } else {
                removeStop[i] = false;
                pattern.stops[j] = originalTripPattern.stops[i];
                pattern.pickups[j] = originalTripPattern.pickups[i];
                pattern.dropoffs[j] = originalTripPattern.dropoffs[i];
                pattern.wheelchairAccessible.set(j, originalTripPattern.wheelchairAccessible.get(i));
                j++;
            }
        }
        // Next, remove the same stops from every trip on this pattern.
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule schedule : originalTripPattern.tripSchedules) {
            pattern.tripSchedules.add(filterSchedule(schedule, removeStop));
        }
        return pattern;
    }

    /**
     * A method that removes stops from TripSchedules. It is applied in the same way to all the TripSchedules within a
     * TripPattern.
     *
     * @param originalSchedule the TripSchedule whose arrival and departure times are to be modified.
     * @param removeStop an array the same length as the original pattern, showing which stops are to be removed.
     * @return a copy of the original TripSchedule with the indicated stops removed.
     */
    private TripSchedule filterSchedule (TripSchedule originalSchedule, boolean[] removeStop) {
        if (removeStop.length != originalSchedule.arrivals.length) {
            throw new AssertionError("Stop removal boolean array should be the same length as the affected pattern.");
        }
        // Make a protective copy of this schedule, so we can modify it without affecting the original.
        TripSchedule schedule = originalSchedule.clone();
        int newLength = removeStop.length - Booleans.countTrue(removeStop);
        schedule.arrivals = new int[newLength];
        schedule.departures = new int[newLength];
        int accumulatedRideTime = 0;
        int prevInputDeparture = 0;
        int prevOutputDeparture = 0;
        for (int i = 0, j = 0; i < removeStop.length; i++) {
            int rideTime = originalSchedule.arrivals[i] - prevInputDeparture;
            int dwellTime = originalSchedule.departures[i] - originalSchedule.arrivals[i];
            prevInputDeparture = originalSchedule.departures[i];
            if (removeStop[i]) {
                // The current stop will not be included in the output. Record the travel time.
                accumulatedRideTime += rideTime;
                if (j == 0) {
                    // Only accumulate dwell time at the beginning of the output trip, to preserve the time offset of
                    // the first arrival in the output. After that, dwells are not retained for skipped stops.
                    accumulatedRideTime += dwellTime;
                }
            } else {
                schedule.arrivals[j] = prevOutputDeparture + accumulatedRideTime + rideTime;
                schedule.departures[j] = schedule.arrivals[j] + dwellTime;
                prevOutputDeparture = schedule.departures[j];
                accumulatedRideTime = 0;
                j++;
            }
        }
        return schedule;
    }

}
