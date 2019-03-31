package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.primitives.Booleans;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scale the speed of travel by a constant factor. That is, uniformly speed trips up or slow them down.
 * This modification can also be applied to only part of a route by specifying a series of "hops", i.e.
 * pairs of stops adjacent to one another in the pattern.
 * We do not have an absolute speed parameter, only a scale parameter, because the server does not necessarily know
 * the route alignment and the inter-stop distances to calculate travel times from speeds.
 * You can specify either routes or patterns to modify, but not both at once. Changing the speed of only some trips
 * on a pattern does not cause problems like adding or removing stops does, so you can also specify individual trips.
 */
public class AdjustSpeed extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(AdjustSpeed.class);

    /** The routes which should be sped up or slowed down. */
    public Set<String> routes;

    /** One example trip on each pattern that should be sped up or slowed down. */
    public Set<String> patterns;

    /** Individual trips that should be sped up or slowed down. */
    public Set<String> trips;

    /** The multiplicative scale factor for speeds. */
    public double scale = -1;

    /**
     * Which stops in the route serve as the fixed points around which trips are contracted or expanded.
     * TODO Implement.
     */
    public List<String> referenceStops;

    /**
     * Hops which should have their speed set or scaled. If not supplied, all hops should be modified.
     * Each hop is a pair of adjacent stop IDs (from/to) and the hop specification is directional.
     */
    public List<String[]> hops;

    /**
     * If true, the scale factor applies to both dwells and inter-stop rides. If false, dwells remain the
     * same length and the scale factor only applies to rides.
     */
    public boolean scaleDwells = false;

    /** These are two parallel arrays of the same length, where (hopFromStops[i], hopToStops[i]) represents one hop. */
    private transient TIntList hopFromStops;

    private transient TIntList hopToStops;

    /** For logging the effects of the modification and reporting an error when the modification has no effect. */
    private int nTripsAffected = 0;

    @Override
    public boolean resolve(TransportNetwork network) {
        if (scale <= 0) {
            errors.add("Scaling factor must be a positive number.");
        }

        if (hops != null) {
            hopFromStops = new TIntArrayList(hops.size());
            hopToStops = new TIntArrayList(hops.size());
            for (String[] pair : hops) {
                if (pair.length != 2) {
                    errors.add("Hops must all have exactly two stops.");
                    continue;
                }
                int intFromId = network.transitLayer.indexForStopId.get(pair[0]);
                int intToId = network.transitLayer.indexForStopId.get(pair[1]);
                if (intFromId == -1) {
                    errors.add("Could not find hop origin stop " + pair[0]);
                    continue;
                }
                if (intToId == -1) {
                    errors.add("Could not find hop destination stop " + pair[1]);
                    continue;
                }
                hopFromStops.add(intFromId);
                hopToStops.add(intToId);
            }
        }
        checkIds(routes, patterns, trips, true, network);
        return errors.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processTripPattern)
                .collect(Collectors.toList());
        if (nTripsAffected > 0) {
            LOG.info("Speed was changed on {} trips.", nTripsAffected);
        } else {
            errors.add("This modification did not cause any changes to the transport network.");
        }
        return errors.size() > 0;
    }

    private TripPattern processTripPattern (TripPattern originalPattern) {
        if (routes != null && !routes.contains(originalPattern.routeId)) {
            // This Modification does not apply to the route this TripPattern is on, TripPattern remains unchanged.
            return originalPattern;
        }
        if (patterns != null && originalPattern.containsNoTrips(patterns)) {
            // This TripPattern does not contain any of the example trips, so its speed is not adjusted.
            return originalPattern;
        }
        if (trips != null && originalPattern.containsNoTrips(trips)) {
            // Avoid unnecessary new lists and cloning when no trips in this pattern are affected.
            return originalPattern;
        }
        // First, decide which hops in this pattern should be scaled.
        boolean[] shouldScaleHop = new boolean[originalPattern.stops.length - 1];
        if (hops == null) {
            Arrays.fill(shouldScaleHop, true);
        } else {
            for (int i = 0; i < originalPattern.stops.length - 1; i++) {
                for (int j = 0; j < hopFromStops.size(); j++) {
                    if (originalPattern.stops[i] == hopFromStops.get(j)
                            && originalPattern.stops[i + 1] == hopToStops.get(j)) {
                        shouldScaleHop[i] = true;
                        break;
                    }
                }
            }
        }
        if (!Booleans.contains(shouldScaleHop, true)) {
            // No hops would be modified. Keep the original pattern unchanged.
            return originalPattern;
        }
        // There are hops that will have their speed changed. Make a shallow protective copy of this TripPattern.
        TripPattern pattern = originalPattern.clone();
        double timeScaleFactor = 1/scale; // Invert speed coefficient to get time coefficient
        int nStops = pattern.stops.length;
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalPattern.tripSchedules) {
            if (trips != null && !trips.contains(originalSchedule.tripId)) {
                // This trip has not been chosen for adjustment.
                pattern.tripSchedules.add(originalSchedule);
                continue;
            }
            TripSchedule newSchedule = originalSchedule.clone();
            pattern.tripSchedules.add(newSchedule);
            newSchedule.arrivals = new int[nStops];
            newSchedule.departures = new int[nStops];
            // Use a floating-point number to avoid accumulating integer truncation error.
            double seconds = originalSchedule.arrivals[0];
            for (int s = 0; s < nStops; s++) {
                int dwellTime = originalSchedule.departures[s] - originalSchedule.arrivals[s];
                newSchedule.arrivals[s] = (int) Math.round(seconds);
                if (scaleDwells) {
                    seconds += dwellTime * timeScaleFactor;
                } else {
                    seconds += dwellTime;
                }
                newSchedule.departures[s] = (int) Math.round(seconds);
                if (s < nStops - 1) {
                    // We are not at the last stop in the pattern, so compute and optionally scale the following hop.
                    int rideTime = originalSchedule.arrivals[s + 1] - originalSchedule.departures[s];
                    if (shouldScaleHop[s]) {
                        seconds += rideTime * timeScaleFactor;
                    } else {
                        seconds += rideTime;
                    }
                }
            }
            int originalTravelTime = originalSchedule.departures[nStops - 1] - originalSchedule.arrivals[0];
            int updatedTravelTime = newSchedule.departures[nStops - 1] - newSchedule.arrivals[0];
            LOG.debug("Total travel time on trip {} changed from {} to {} seconds.",
                    newSchedule.tripId, originalTravelTime, updatedTravelTime);
            nTripsAffected += 1;
            postSanityCheck(newSchedule);
        }
        LOG.debug("Scaled speeds (factor {}) for all trips on {}.", scale, originalPattern);
        return pattern;
    }

    private static void postSanityCheck (TripSchedule schedule) {
        // TODO check that modified trips still make sense after applying the modification
        // This should be called in any Modification that changes a schedule.
    }

    public int getSortOrder() { return 0; }

}
