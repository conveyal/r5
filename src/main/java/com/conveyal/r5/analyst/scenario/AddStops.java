package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Insert one or more stops into some trip patterns.
 * This splices a new chunk of timetable into an existing route.
 * If both fromStop and toStop are specified, the part of the timetable in between those stops is replaced.
 * If fromStop is null, then the whole timetable is replaced up to the toStop.
 * If toStop in null, then the whole timetable is replaced after fromStop.
 * One dwell time should be supplied for each new stop.
 * When both fromStop and toStop are specified, there should be one more hop time than there are new stops.
 * If one of fromStop or toStop is not specified, then there should be the same number of hop times as stops.
 * Inserting stops into some trips but not others on a pattern could create new patterns. Therefore we only allow
 * applying this modification to entire routes, which ensures that it is applied to entire patterns.
 */
public class AddStops extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(AddStops.class);

    /** On which routes the stop should be inserted. */
    public Set<String> routes;

    /** Stop after which to insert the new stops. */
    public String fromStop;

    /** Stop before which to insert the new stops. */
    public String toStop;

    /** Stops to insert, replacing everything between fromStop and toStop in the given routes. */
    public List<StopSpec> stops;

    /** Number of seconds the vehicle will remain immobile at each of the new stops. */
    public int[] dwellTimes;

    /**
     * Number of seconds the vehicle will spend traveling between each of the new stops.
     * There should be one more hop time than dwell times if both of fromStop and toStop are specified.
     */
    public int[] hopTimes;

    /* The internal integer ID of the stop is looked up only once and cached before the modification is applied. */
    private int intFromStop = -1;

    /* The internal integer ID of the stop is looked up only once and cached before the modification is applied. */
    private int intToStop = -1;

    /**
     * The internal integer IDs of all the new stops to be added.
     * These are looked up or created and cached before the modification is applied.
     */
    private TIntList intNewStops = new TIntArrayList();

    @Override
    public String getType() {
        return "add-stops";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        if (fromStop == null && toStop == null) {
            warnings.add("At least one of from and to stop must be supplied.");
        }
        if (fromStop != null) {
            intFromStop = network.transitLayer.indexForStopId.get(fromStop);
            if (intFromStop == -1) {
                warnings.add("Could not find fromStop with GTFS ID " + fromStop);
            }
        }
        if (toStop != null) {
            intToStop = network.transitLayer.indexForStopId.get(toStop);
            if (intToStop == -1) {
                warnings.add("Could not find toStop with GTFS ID " + toStop);
            }
        }
        if (stops == null) {
            // It is fine to add no new stops,
            // as long as there is a single hop expressing the travel time from fromStop to toStop.
            stops = new ArrayList<>();
        }
        if (dwellTimes == null) {
            dwellTimes = new int[0];
        }
        if (dwellTimes == null || dwellTimes.length != stops.size()) {
            warnings.add("The number of dwell times must exactly match the number of new stops.");
        }
        if (hopTimes == null) {
            warnings.add("You must always supply some hop times.");
        } else {
            int expectedHops = stops.size();
            if (fromStop != null && toStop != null) {
                expectedHops += 1;
            }
            if (hopTimes.length != expectedHops) {
                warnings.add("The number of hops must equal the number of new stops (one less if one endpoint is not specified).");
            }
        }
        intNewStops = findOrCreateStops(stops, network);
        return warnings.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processTripPattern)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return warnings.size() > 0;
    }

    private TripPattern processTripPattern (TripPattern originalTripPattern) {
        if (routes != null && !routes.contains(originalTripPattern.routeId)) {
            // This pattern is not on one of the specified routes. The trip pattern should remain unchanged.
            return originalTripPattern;
        }
        // The number of elements to copy from the original stops array before the new segment is spliced in.
        int insertBeginIndex = -1;
        // The element of the original stops array at which copying resumes after the new segment is spliced in.
        int insertEndIndex = -1;
        for (int s = 0; s < originalTripPattern.stops.length; s++) {
            if (originalTripPattern.stops[s] == intFromStop) {
                insertBeginIndex = s + 1;
            }
            if (originalTripPattern.stops[s] == intToStop) {
                insertEndIndex = s;
            }
        }
        if (intFromStop == -1) {
            insertBeginIndex = 0;
        }
        if (intToStop == -1) {
            insertEndIndex = originalTripPattern.stops.length;
        }
        if (insertBeginIndex == -1 || insertEndIndex == -1) {
            // We are missing either the beginning or the end of the insertion slice. This TripPattern does not match.
            return originalTripPattern;
        }
        if (insertEndIndex < insertBeginIndex) {
            warnings.add("The end of the insertion region must be at or after its beginning.");
            return originalTripPattern;
        }
        TripPattern pattern = originalTripPattern.clone();
        int nStopsToRemove = insertEndIndex - insertBeginIndex;
        int oldLength = pattern.stops.length;
        int newLength = (oldLength + intNewStops.size()) - nStopsToRemove;
        pattern.stops = new int[newLength];
        pattern.pickups = new PickDropType[newLength];
        pattern.dropoffs = new PickDropType[newLength];
        pattern.wheelchairAccessible = new BitSet(newLength);
        // Copy the original pattern to the new one, inserting or leaving out stops as necessary.
        // i is the index within the source (original) pattern, j is the index within the target (new) pattern.
        for (int i = 0, j = 0; i < oldLength; i++) {
            if (i == insertBeginIndex) {
                // Splice in the new schedule fragment here.
                for (int ns = 0; ns < intNewStops.size(); ns++) {
                    pattern.stops[j] = intNewStops.get(ns);
                    pattern.pickups[j] = PickDropType.SCHEDULED;
                    pattern.dropoffs[j] = PickDropType.SCHEDULED;
                    pattern.wheelchairAccessible.set(j, pattern.wheelchairAccessible.get(0));
                    j++;
                }
            }
            if (i >= insertBeginIndex && i < insertEndIndex) {
                // Skip over some stops in the original (source) trip pattern.
                continue;
            }
            pattern.stops[j] = originalTripPattern.stops[i];
            pattern.pickups[j] = originalTripPattern.pickups[i];
            pattern.dropoffs[j] = originalTripPattern.dropoffs[i];
            pattern.wheelchairAccessible.set(j, originalTripPattern.wheelchairAccessible.get(i));
            j++;
        }
        // TODO Next, perform the same splicing operation for every trip on this pattern.
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalTripPattern.tripSchedules) {
            TripSchedule schedule = originalSchedule.clone();
            pattern.tripSchedules.add(schedule);
            schedule.arrivals = new int[newLength];
            schedule.departures = new int[newLength];
            int prevOutputDeparture = 0;
            // Iterate over all stops in the source pattern. We have an arrival and a departure for each of these stops.
            for (int s = 0, j = 0; s < oldLength; s++) {
                if (s == insertBeginIndex) {
                    // Splice in the new schedule fragment here.
                    // FIXME this is not going to work replacing the end of a route.
                    for (int hop = 0; hop < hopTimes.length; hop++) {
                        schedule.arrivals[j] = prevOutputDeparture + hopTimes[hop];
                        schedule.departures[j] = schedule.arrivals[j];
                        if (hop < dwellTimes.length) {
                            schedule.departures[j] += dwellTimes[hop];
                        }
                        prevOutputDeparture = schedule.departures[j];
                        j++;
                    }
                } else {
                    int rideTime = originalSchedule.arrivals[s];
                    if (s > 0) {
                        rideTime -= originalSchedule.departures[s - 1];
                    }
                    schedule.arrivals[j] = prevOutputDeparture + rideTime;
                }
                int dwellTime = originalSchedule.departures[s] - originalSchedule.arrivals[s];
                schedule.departures[j] = schedule.arrivals[j] + dwellTime;
                prevOutputDeparture = schedule.departures[j];
            }
        }
        return pattern;
    }

    @Override
    public boolean affectsStreetLayer() {
        return stops.stream().anyMatch(s -> s.id == null);
    }

}
