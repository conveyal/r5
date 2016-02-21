package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.primitives.Booleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Set;

/**
 * Insert a new stop into some trip patterns, adjusting travel times to account for dwell times at the stop.
 * For each stop pattern on the specified route(s), a single insertion point is identified.
 * The list of existing stops is scanned, and the new stop is inserted immediately after the first
 * afterStopId encountered. This allows inserting the same stop into multiple patterns on the same route,
 * such as branches converging on a common trunk or opposite directions of the same route.
 */
public class InsertStop extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(InsertStop.class);

    /** On which routes the stop should be inserted. */
    public Set<String> routeId;

    /** Stop to insert. */
    public String insertStop;

    /**
     * After which existing stop should it be inserted? Several values can be provided to allow inserting in both
     * directions or on different branches converging on a common trunk.
     */
    public Set<String> afterStop;

    /** Number of seconds the vehicle will remain immobile at the new stop. */
    public int dwellTime;

    /** Seconds of deceleration, acceleration, and detour required by the new stop, not including dwell time. */
    public int extraTravelTime;

    private transient TransitLayer transitLayer;

    @Override
    public String getType() {
        return "insert-stop";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        return false;
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        boolean errorsFound = false;
        // This is silly. We should find a better way to make this information always available.
        this.transitLayer = network.transitLayer;
        int intStopId = transitLayer.indexForStopId.get(insertStop);
        if (intStopId == 0) { // FIXME missing value should be -1 instead of 0
            warnings.add("Could not find a stop to insert having GTFS ID " + insertStop);
            errorsFound = true;
        }
        for (String stringStopId : afterStop) {
            intStopId = transitLayer.indexForStopId.get(stringStopId);
            if (intStopId == 0) { // FIXME missing value should be -1 instead of 0
                warnings.add("Could not find insert-after stop having GTFS ID " + insertStop);
                errorsFound = true;
            }
        }
        return errorsFound;
    }

    private TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        // TODO match individual trips. For now this can only handle entire routes.
        if (!routeId.contains(originalTripPattern.routeId)) {
            return originalTripPattern;
        }
        // Find an insertion point
        boolean found = false;
        int insertionPoint = 0;
        for (int stop : originalTripPattern.stops) {
            if (afterStop.contains(transitLayer.stopIdForIndex.get(stop))) {
                found = true;
                break;
            }
            insertionPoint += 1;
        }
        if (!found) {
            LOG.info("No insertion point found on {}", originalTripPattern);
            return originalTripPattern;
        }
        // We have an insertion point. Make a protective copy and modify the trip pattern.
        LOG.info("Inserting stop {} at position {} of {}", insertStop, insertionPoint, originalTripPattern);
        TripPattern pattern = originalTripPattern.clone();
        int oldLength = pattern.stops.length;
        int newLength = oldLength + 1;
        pattern.stops = new int[newLength];
        pattern.pickups = new PickDropType[newLength];
        pattern.dropoffs = new PickDropType[newLength];
        pattern.wheelchairAccessible = new BitSet(newLength);
        for (int i = 0, j = 0; i < oldLength; i++, j++) {
            pattern.stops[j] = originalTripPattern.stops[i];
            pattern.pickups[j] = originalTripPattern.pickups[i];
            pattern.dropoffs[j] = originalTripPattern.dropoffs[i];
            pattern.wheelchairAccessible.set(j, originalTripPattern.wheelchairAccessible.get(i));
            if (i == insertionPoint) {
                j++;
                pattern.stops[j] = transitLayer.indexForStopId.get(insertStop); // TODO check that lookup succeeded
                pattern.pickups[j] = PickDropType.SCHEDULED;
                pattern.dropoffs[j] = PickDropType.SCHEDULED;
                pattern.wheelchairAccessible.set(j, pattern.wheelchairAccessible.get(0));
            }
        }
        // Next, insert the stop into a copy of every trip on this pattern.
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalTripPattern.tripSchedules) {
            // TODO factor out into another function?
            TripSchedule schedule = originalSchedule.clone();
            schedule.arrivals = new int[newLength];
            schedule.departures = new int[newLength];
            int prevInputDeparture = 0;
            int prevOutputDeparture = 0;
            for (int i = 0, j = 0; i < oldLength; i++, j++) {
                int rideTime = originalSchedule.arrivals[i] - prevInputDeparture;
                int dwellTime = originalSchedule.departures[i] - originalSchedule.arrivals[i];
                prevInputDeparture = originalSchedule.departures[i];
                if (i == insertionPoint + 1) {
                    rideTime += extraTravelTime;
                    int rideTimeBefore = rideTime / 2; // TODO calculate real distance proportions
                    int rideTimeAfter = rideTime - rideTimeBefore;
                    schedule.arrivals[j] = prevOutputDeparture + rideTimeBefore;
                    // Add the dwell time specified for the new stop, not the dwell time derived from the input pattern.
                    schedule.departures[j] = schedule.arrivals[j] + this.dwellTime;
                    prevOutputDeparture = schedule.departures[j];
                    rideTime = rideTimeAfter;
                    j++;
                }
                schedule.arrivals[j] = prevOutputDeparture + rideTime;
                schedule.departures[j] = schedule.arrivals[j] + dwellTime;
                prevOutputDeparture = schedule.departures[j];
            }
            pattern.tripSchedules.add(schedule);
        }
        return pattern;
    }

}
