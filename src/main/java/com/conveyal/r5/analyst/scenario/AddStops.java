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

import java.util.*;
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
        // It is fine to add no new stops,
        // as long as there is a single hop expressing the travel time from fromStop to toStop.
        if (stops == null) {
            stops = new ArrayList<>();
        }
        if (dwellTimes == null) {
            dwellTimes = new int[0];
        }
        if (dwellTimes.length != stops.size()) {
            warnings.add("The number of dwell times must exactly match the number of new stops.");
        }
        if (hopTimes == null) {
            warnings.add("You must always supply some hop times.");
        } else {
            // In the case where a from or to stop is not spcified for the insertion.
            int expectedHops = stops.size();
            // Adjust for the case where we are not inserting at the beginning or end of a pattern.
            if (fromStop != null && toStop != null) {
                expectedHops += 1;
            }
            if (hopTimes.length != expectedHops) {
                warnings.add("The number of hops must equal the number of new stops (one less if fromStop or toStop is not specified).");
            }
        }
        intNewStops = findOrCreateStops(stops, network);
        return warnings.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {
        this.network = network;
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processTripPattern)
                .collect(Collectors.toList());
        return warnings.size() > 0;
    }

    private TransportNetwork network;

    /**
     * Given a single TripPattern, if this Modification changes that TripPattern, return a modified copy of it.
     * Otherwise return the original, unchanged and uncopied TripPattern.
     */
    private TripPattern processTripPattern (TripPattern originalTripPattern) {
        if (routes != null && !routes.contains(originalTripPattern.routeId)) {
            // This pattern is not on one of the specified routes. The trip pattern should remain unchanged.
            return originalTripPattern;
        }
        // The number of elements to copy from the original stops array before the new segment is spliced in.
        // That is, the first element in the output stops array that will contain a stop from the new segment.
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

        // Create a protective copy of this TripPattern whose arrays are shorter or longer as needed.
        TripPattern pattern = originalTripPattern.clone();
        int nStopsToRemove = insertEndIndex - insertBeginIndex;
        int oldLength = pattern.stops.length;
        int newLength = (oldLength + intNewStops.size()) - nStopsToRemove;
        pattern.stops = new int[newLength];
        pattern.pickups = new PickDropType[newLength];
        pattern.dropoffs = new PickDropType[newLength];
        pattern.wheelchairAccessible = new BitSet(newLength);

        // Copy the original pattern to the new one, inserting or leaving out stops as necessary.
        // This is copying the stops, PickDropTypes, etc. for the whole pattern.
        // Trip timetables will be modified separately below.
        int ss = 0; // the index within the source (original) pattern
        int ts = 0; // the index within the target (new) pattern.
        while (ts < newLength) {

            // Splice in the new stops when we reach the appropriate location in the source pattern.
            if (ss == insertBeginIndex) {
                for (int ns = 0; ns < intNewStops.size(); ns++) {
                    pattern.stops[ts] = intNewStops.get(ns);
                    pattern.pickups[ts] = PickDropType.SCHEDULED;
                    pattern.dropoffs[ts] = PickDropType.SCHEDULED;
                    // Assume newly created stops are wheelchair accessible.
                    // FIXME but we should be copying wheelchair accessibility value for existing inserted stops
                    pattern.wheelchairAccessible.set(ts, true);
                    // Increment target stop but not source stop, since we have not consumed any source pattern stops.
                    ts++;
                }
            }

            // Skip over the stops in the original (source) trip pattern that are to be replaced.
            if (ss >= insertBeginIndex && ss < insertEndIndex) {
                ss++;
            }

            // Copy one stop from the source to the target
            // This code will be executed unless we are splicing the new stops onto the end of the pattern.
            if (ts < newLength) {
                pattern.stops[ts] = originalTripPattern.stops[ss];
                pattern.pickups[ts] = originalTripPattern.pickups[ss];
                pattern.dropoffs[ts] = originalTripPattern.dropoffs[ss];
                pattern.wheelchairAccessible.set(ts, originalTripPattern.wheelchairAccessible.get(ss));
                ss++;
                ts++;
            }
        }
        LOG.info("Old stop sequence: {}", originalTripPattern.stops);
        LOG.info("New stop sequence: {}", pattern.stops);
        LOG.info("Old stop IDs: {}", Arrays.stream(originalTripPattern.stops)
                .mapToObj(network.transitLayer.stopIdForIndex::get).collect(Collectors.toList()));
        LOG.info("New stop IDs: {}", Arrays.stream(pattern.stops)
                .mapToObj(network.transitLayer.stopIdForIndex::get).collect(Collectors.toList()));

        // Perform the same splicing operation for every trip on this pattern.
        // Here we have to deal with both stops and the hops between them.
        pattern.tripSchedules = new ArrayList<>();
        for (TripSchedule originalSchedule : originalTripPattern.tripSchedules) {

            // Make a protective copy of this TripSchedule, changing the length of its arrays as needed.
            TripSchedule schedule = originalSchedule.clone();
            pattern.tripSchedules.add(schedule);
            schedule.arrivals = new int[newLength];
            schedule.departures = new int[newLength];

            // Track the departure time from the last written stop in the output across iterations.
            int prevOutputDeparture = 0;
            // TODO calculate all arrivals and departures zero-based, and then shift them at the end to maintain fixed-point stop.
            // The fixed-point stop should be different depending on whether we are splicing onto the beginning or end of the route

            ss = 0; // Source stop position in pattern
            ts = 0; // Target stop position in pattern

            // Iterate until we've filled all slots in the target pattern.
            while (ts < newLength) {

                // Splice in the new schedule fragment when we reach the right place in the source pattern.
                int spliceArrivalTime = -1;
                if (ss == insertBeginIndex) {
                    // NOTE dwellTimes[hop] is not always the dwell after hopTimes[hop].
                    // The number of dwells is always equal to the number of hops or one less,
                    // so we iterate over all hops but track the dwell index separately.
                    int hop = 0, dwell = 0;
                    while (hop < hopTimes.length) {
                        if (ss == 0 && dwell == 0) {
                            // Inserting at the beginning of the route. On the first stop there is no preceding hop.
                            // Consume one dwell, but no hop.
                            schedule.arrivals[ts] = originalSchedule.arrivals[0];
                            schedule.departures[ts] = schedule.arrivals[ts] + dwellTimes[dwell];
                            prevOutputDeparture = schedule.departures[ts];
                            dwell++;
                            ts++;
                            // Hop is not incremented because we did not consume one.
                            // continue;
                        }
                        // Always consume a hop, but only save arrival and departure times if there's a dwell.
                        spliceArrivalTime = prevOutputDeparture + hopTimes[hop];
                        hop++;
                        // There will only be a final dwell if we're splicing onto the end of the pattern.
                        // Otherwise arrivalTime will be used by the outer loop to save the next arrival / departure.
                        if (dwell < dwellTimes.length) {
                            schedule.arrivals[ts] = spliceArrivalTime;
                            schedule.departures[ts] = schedule.arrivals[ts] + dwellTimes[dwell];
                            prevOutputDeparture = schedule.departures[ts];
                            dwell++;
                            ts++;
                        }
                    }
                    if (hop != hopTimes.length || dwell != dwellTimes.length) {
                        throw new AssertionError("Hop and dwell arrays with validated lengths were not exhausted. THIS IS A BUG");
                    }
                }

                // Skip over the part of the source pattern that was replaced by the new schedule fragment.
                // We don't use 'continue' to do this because we want to retain the arrivalTime set during insertion.
                while (ss >= insertBeginIndex && ss < insertEndIndex) {
                    ss++;
                }

                // Accumulate the remaining ride and dwell times from the source schedule.
                // This block will be run unless we're splicing onto the end of the pattern.
                if (ts < newLength) {
                    int arrivalTime = spliceArrivalTime;
                    if (arrivalTime < 0) {
                        // If the splicing process did not supply an arrival time,
                        // use a hop time from the source pattern.
                        // At stop zero, there is no preceding hop.
                        int hopTime = originalSchedule.arrivals[ss];
                        if (ss > 0) {
                            hopTime -= originalSchedule.departures[ss - 1];
                        }
                        arrivalTime = prevOutputDeparture + hopTime;
                    }
                    // If the new segment was just inserted on this iteration, that process has reset the arrival time.
                    schedule.arrivals[ts] = arrivalTime;
                    int dwellTime = originalSchedule.departures[ss] - originalSchedule.arrivals[ss];
                    schedule.departures[ts] = schedule.arrivals[ts] + dwellTime;
                    prevOutputDeparture = schedule.departures[ts];
                    ts++;
                    ss++;
                }
            }
            LOG.info("Original arrivals:   {}", originalSchedule.arrivals);
            LOG.info("Original departures: {}", originalSchedule.departures);
            LOG.info("Modified arrivals:   {}", schedule.arrivals);
            LOG.info("Modified departures: {}", schedule.departures);
        }
        return pattern;
    }

    @Override
    public boolean affectsStreetLayer() {
        return stops.stream().anyMatch(s -> s.id == null);
    }

}
