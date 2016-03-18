package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.primitives.Booleans;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Remove stops and the associated dwell times from trips.
 * Skipped stops are no longer served by the matched trips, and and dwell time at a skipped stop is removed from the schedule.
 * If stops are skipped at the start of a trip, the start of the trip is simply removed; the remaining times are not shifted.
 *
 * If you remove stops from some but not all trips on a pattern, you've created another possibly new pattern.
 * Therefore we don't provide any way to remove stops from individual trips. You can only remove them from entire routes
 * and if you remove stops from an entire route, you automatically remove them from every trip in every pattern.
 *
 * Also, there's no way to remove only one instance of a stop that occurs multiple times (e.g. in a loop route).
 * This is a known and accepted limitation.
 */
public class RemoveStops extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(RemoveStops.class);

    /** On which route the stops should be skipped. */
    public String routes;

    /** Stops to skip. Note that setting this to null as a wildcard is not supported, obviously. */
    public Set<String> stops;

    /** Internal integer IDs for a specific transit network, converted from the string IDs. */
    private transient TIntSet intStopIds;

    /** This will be set to true if any errors occur while resolving String-based IDs against the network. */
    private boolean errorsResolving = false;

    /** This will be set to true if any errors occur while applying the modification to a network. */
    private boolean errorsApplying = false;

    @Override
    public String getType() {
        return "remove-stops";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        intStopIds = new TIntHashSet();
        for (String stringStopId : stops) {
            int intStopId = network.transitLayer.indexForStopId.get(stringStopId);
            if (intStopId == 0) { // FIXME should be -1 not 0
                warnings.add("Could not find a stop with GTFS ID " + stringStopId);
                errorsResolving = true;
            } else {
                intStopIds.add(intStopId);
                // LOG.debug("Stop selected for removal: {}", network.transitLayer.stopNames.get(intStopId));
            }
        }
        LOG.debug("Resolved stop IDs for removal. Strings {} resolved to integers {}.", stops, intStopIds);
        return errorsResolving;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(tp -> this.applyToTripPattern(tp))
                .collect(Collectors.toList());
        return errorsApplying;
    }

    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        if (!routes.contains(originalTripPattern.routeId)) {
            return originalTripPattern;
        }
        int nToRemove = (int) Arrays.stream(originalTripPattern.stops).filter(intStopIds::contains).count();
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
            if (intStopIds.contains(originalTripPattern.stops[i])) {
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
        pattern.tripSchedules = originalTripPattern.tripSchedules.stream()
                .map(schedule -> filterSchedule(schedule, removeStop))
                .collect(Collectors.toList());
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
        TripSchedule newSchedule = originalSchedule.clone();
        int newLength = removeStop.length - Booleans.countTrue(removeStop);
        newSchedule.arrivals = new int[newLength];
        newSchedule.departures = new int[newLength];
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
                newSchedule.arrivals[j] = prevOutputDeparture + accumulatedRideTime + rideTime;
                newSchedule.departures[j] = newSchedule.arrivals[j] + dwellTime;
                prevOutputDeparture = newSchedule.departures[j];
                accumulatedRideTime = 0;
                j++;
            }
        }
        return newSchedule;
    }

}
