package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adjust the dwell times on matched routes, patterns, or trips.
 * This could reasonably be combined with adjust-speed, but adjust-speed requires a more complicated hop-based
 * description of which stops will be affected. This modification type exists for simplicity from the user perspective.
 * Supply only one of dwellSecs or scale, and supply exactly one of routes, patterns, or trips.
 */
public class AdjustDwellTime extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(AdjustDwellTime.class);

    public static final long serialVersionUID = 1L;

    /** The routes which should have dwell times changed. */
    public Set<String> routes;

    /** One or more example tripIds from every pattern that should have its dwell times changed. */
    public Set<String> patterns;

    /** Trips which should have dwell times changed. */
    public Set<String> trips;

    /** Stops at which to change the dwell times. If not specified, all dwell times will be changed. */
    public Set<String> stops;

    /** New dwell time in seconds. */
    public int dwellSecs = -1;

    /** Multiplicative factor to stretch or shrink the dwell times. */
    public double scale = -1;

    /** The internal integer IDs for the stops to be adjusted, resolved once before the modification is applied. */
    private transient TIntSet intStops;

    /** For logging the effects of the modification and catching errors where nothing is changed. */
    private int nTripsAffected = 0;

    @Override
    public boolean resolve (TransportNetwork network) {
        if (stops != null) {
            intStops = new TIntHashSet();
            for (String stringStopId : stops) {
                int intStopId = network.transitLayer.indexForStopId.get(stringStopId);
                if (intStopId == -1) {
                    errors.add("Could not find a stop to adjust with GTFS ID " + stringStopId);
                } else {
                    intStops.add(intStopId);
                }
            }
            LOG.info("Resolved stop IDs for removal. Strings {} resolved to integers {}.", stops, intStops);
        }
        // Not bitwise operator: non-short-circuit logical XOR.
        if (!((dwellSecs >= 0) ^ (scale >= 0))) {
            errors.add("Dwell time or scaling factor must be specified, but not both.");
        }
        checkIds(routes, patterns, trips, true, network);
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processTripPattern)
                .collect(Collectors.toList());
        if (nTripsAffected > 0) {
            LOG.info("Modified {} trips.", nTripsAffected);
        } else {
            errors.add("This modification did not affect any trips.");
        }
        return errors.size() > 0;
    }

    private TripPattern processTripPattern (TripPattern originalPattern) {
        if (routes != null && !routes.contains(originalPattern.routeId)) {
            // This TripPattern is not on a route that has been chosen for adjustment.
            return originalPattern;
        }
        if (patterns != null && originalPattern.containsNoTrips(patterns)) {
            // This TripPattern does not contain any of the example trips, Modification does not apply.
            return originalPattern;
        }
        if (trips != null && originalPattern.containsNoTrips(trips)) {
            // Avoid unnecessary new lists and cloning when no trips in this pattern are affected.
            return originalPattern;
        }
        // Make a shallow protective copy of this TripPattern.
        TripPattern newPattern = originalPattern.clone();
        int nStops = newPattern.stops.length;
        newPattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalPattern.tripSchedules) {
            if (trips != null && !trips.contains(originalSchedule.tripId)) {
                // This trip has not been chosen for adjustment.
                newPattern.tripSchedules.add(originalSchedule);
                continue;
            }
            TripSchedule newSchedule = originalSchedule.clone();
            newPattern.tripSchedules.add(newSchedule);
            newSchedule.arrivals = new int[nStops];
            newSchedule.departures = new int[nStops];
            // Use a floating-point number to avoid accumulating integer truncation error.
            double seconds = originalSchedule.arrivals[0];
            for (int s = 0; s < nStops; s++) {
                newSchedule.arrivals[s] = (int) Math.round(seconds);
                // use double here as well, continue to avoid truncation error
                // consider the case case where you're halving dwell times of 19 seconds; truncation error would build
                // up half a second per stop.
                double dwellTime = originalSchedule.departures[s] - originalSchedule.arrivals[s];
                if (stops == null || intStops.contains(newPattern.stops[s])) {
                    if (dwellSecs >= 0) {
                        dwellTime = dwellSecs;
                    } else {
                        dwellTime *= scale;
                    }
                }
                seconds += dwellTime;
                newSchedule.departures[s] = (int) Math.round(seconds);
                if (s < nStops - 1) {
                    // We are not at the last stop in the pattern, so compute and accumulate the following hop.
                    int rideTime = originalSchedule.arrivals[s + 1] - originalSchedule.departures[s];
                    seconds += rideTime;
                }
            }
            nTripsAffected += 1;
        }
        return newPattern;
    }

    public int getSortOrder() { return 10; }

}
