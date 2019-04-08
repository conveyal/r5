package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.fasterxml.jackson.annotation.JsonInclude;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Remove stops and the associated dwell times from all trips in the given routes.
 * Removed stops are no longer served, and dwell time at a skipped stop is removed from the schedule.
 * If stops are skipped at the start of a trip, the remaining times are not shifted.
 *
 * If you remove stops from some but not all trips on a pattern, you've created another possibly new pattern.
 * Therefore we don't provide any way to remove stops from individual trips. You can only remove them from patterns or
 * entire routes (in which case you automatically remove them from every trip in every pattern on that route).
 *
 * Also, there's no way to remove only one instance of a stop that occurs multiple times (e.g. in a loop route).
 * This is a known and accepted limitation.
 *
 * TODO allow specifying a fixed point stop around which arrival and departure times are adjusted.
 * TODO make removing dwell times optional.
 */
public class RemoveStops extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(RemoveStops.class);

    /** On which routes the stops should be skipped. */
    public Set<String> routes;

    /** One or more example tripIds on each pattern where the stops should be skipped. */
    public Set<String> patterns;

    /** Stops to remove from the routes. */
    public Set<String> stops;

    /**
     * Number of seconds (in addition to dwell time, if any) to remove from the schedule at each removed stop,
     * to account for vehicle acceleration, deceleration, merging into traffic, and so on.
     *
     * If removing this time would make the hop time between two stops that remain <= 0, a warning will be surfaced.
     */
    // Since not all versions of R5 can handle this parameter, don't include it if it is set at its default value
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int secondsSavedAtEachStop = 0;

    /** Internal integer IDs for a specific transit network, converted from the string IDs. */
    private transient TIntSet intStops;

    /** For logging the effects of the modification and reporting an error when the modification has no effect. */
    private int nPatternsAffected = 0;

    @Override
    public boolean resolve (TransportNetwork network) {
        checkIds(routes, patterns, null, false, network);
        intStops = new TIntHashSet();
        if (stops == null || stops.isEmpty()) {
            errors.add("You must supply some stops to remove.");
        } else {
            for (String stringStopId : stops) {
                int intStopId = network.transitLayer.indexForStopId.get(stringStopId);
                if (intStopId == -1) {
                    errors.add("Could not find a stop with GTFS ID " + stringStopId);
                } else {
                    intStops.add(intStopId);
                }
            }
            LOG.info("Resolved stop IDs for removal. Strings {} resolved to integers {}.", stops, intStops);
        }
        return errors.size() > 0; // TODO make a function for this on the superclass
    }

    @Override
    public boolean apply(TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(p -> this.processTripPattern(p, network))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (nPatternsAffected > 0) {
            LOG.info("Stops were removed from {} patterns.", nPatternsAffected);
        } else {
            errors.add("No patterns had any stops removed by this modification.");
        }
        return errors.size() > 0;
    }

    public TripPattern processTripPattern (TripPattern originalTripPattern, TransportNetwork network) {
        if (routes != null && !routes.contains(originalTripPattern.routeId)) {
            // Modification does not apply to the route this TripPattern is on, so TripPattern remains unchanged.
            return originalTripPattern;
        }
        if (patterns != null && originalTripPattern.containsNoTrips(patterns)) {
            // This TripPattern does not contain any of the example trips, Modification does not apply here.
            return originalTripPattern;
        }
        int nToRemove = (int) Arrays.stream(originalTripPattern.stops).filter(intStops::contains).count();
        if (nToRemove == 0) {
            // The new pattern would be identical to the original.
            return originalTripPattern;
        }
        LOG.debug("Modifying {}", originalTripPattern);
        nPatternsAffected += 1;
        if (nToRemove == originalTripPattern.stops.length) {
            // The new pattern would be empty.
            return null;
        }
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
            if (intStops.contains(originalTripPattern.stops[i])) {
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
        // Next, remove the same stops from every trip on this pattern by copying all its TripSchedules.
        // This is not pulled out into a separate method because it's convenient to reuse a lot of information
        // from one TripSchedule to the next on the same TripPattern.
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalTripPattern.tripSchedules) {
            // Make a protective copy of this schedule, so we can modify it without affecting the original.
            TripSchedule newSchedule = originalSchedule.clone();
            pattern.tripSchedules.add(newSchedule);
            newSchedule.arrivals = new int[newLength];
            newSchedule.departures = new int[newLength];
            int accumulatedRideTime = 0;
            int prevInputDeparture = 0;
            int prevOutputDeparture = 0;
            int nStopsRemovedSinceLastStop = 0;
            for (int i = 0, j = 0; i < removeStop.length; i++) {
                int rideTime = originalSchedule.arrivals[i] - prevInputDeparture;
                int dwellTime = originalSchedule.departures[i] - originalSchedule.arrivals[i];
                prevInputDeparture = originalSchedule.departures[i];
                if (removeStop[i]) {
                    // The current stop will not be included in the output. Record the travel time and subtract off the
                    // seconds saved. This may cause accumulatedRideTime to go negative, which may be fine since we're putting
                    // all the time savings before the stop. After we've accumulated ride time for a whole segment,
                    // we check to make sure it's positive.
                    accumulatedRideTime += rideTime;
                    nStopsRemovedSinceLastStop++;
                    if (j == 0) {
                        // Only accumulate dwell time at the beginning of the output trip, to preserve the time offset of
                        // the first arrival in the output. After that, dwells are not retained for skipped stops.
                        accumulatedRideTime += dwellTime;
                    }
                } else {
                    accumulatedRideTime += rideTime;
                    int secondsToRemove = secondsSavedAtEachStop * nStopsRemovedSinceLastStop;
                    if (nStopsRemovedSinceLastStop > 0 && accumulatedRideTime < secondsToRemove) {
                        // Warn the user if removing the requested amount of time would cause negative travel times,
                        // and clamp travel time at 1 second.
                        // figure out which stops are causing the problem
                        int[] problemStops = new int[nStopsRemovedSinceLastStop];
                        for (int removedStop = i - 1, index = nStopsRemovedSinceLastStop - 1; removedStop >= i - nStopsRemovedSinceLastStop; removedStop--, index--) {
                            problemStops[index] = originalTripPattern.stops[removedStop];
                        }

                        // figure out how many seconds we can remove
                        // the rounding may cause this to be off a bit but that's okay
                        int secondsWeWillActuallyRemovePerStop = (accumulatedRideTime - 1) / nStopsRemovedSinceLastStop;
                        generateNegativeTravelTimeWarning(problemStops, originalSchedule.tripId, secondsWeWillActuallyRemovePerStop, network);

                        // clamp the new hop time to be nonzero and nonnegative
                        secondsToRemove = accumulatedRideTime - 1;
                    }

                    newSchedule.arrivals[j] = prevOutputDeparture + accumulatedRideTime - secondsToRemove;
                    newSchedule.departures[j] = newSchedule.arrivals[j] + dwellTime;
                    prevOutputDeparture = newSchedule.departures[j];
                    accumulatedRideTime = 0;
                    nStopsRemovedSinceLastStop = 0;
                    j++;
                }
            }
        }
        return pattern;
    }

    /** Format a warning about negative travel times and insert it into the warnings array */
    private void generateNegativeTravelTimeWarning(int[] problemStops, String tripId, int secondsWeWillActuallyRemovePerStop, TransportNetwork network) {
        // map them back to names
        String problemStopNames = IntStream.of(problemStops)
                .mapToObj(problemStopIndex ->
                        String.format("\"%s\" (%s)",
                                network.transitLayer.stopNames.get(problemStopIndex),
                                network.transitLayer.stopIdForIndex.get(problemStopIndex)))
                .collect(Collectors.joining(", "));

        String warning = String.format(
                "Removing the requested %d seconds at stops %s on trip %s would cause negative travel time. Removing %d seconds at each instead, leaving 1 second of travel time for whole segment.",
                secondsSavedAtEachStop,
                problemStopNames,
                tripId,
                secondsWeWillActuallyRemovePerStop
        );

        warnings.add(warning);
        LOG.info(warning);

    }

    public int getSortOrder() { return 30; }

}
