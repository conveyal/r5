package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
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
 * Insert a new route segment into some trip patterns.
 * This may create new stops or reference existing ones, splicing a new chunk of timetable into/onto an existing route.
 *
 * If both fromStop and toStop are specified, the part of the timetable in between those stops is replaced.
 * If fromStop is null, then the whole timetable is replaced up to the toStop.
 * If toStop in null, then the whole timetable is replaced after fromStop.
 *
 * The number of dwell times specified must always be one greater than the number of hop times.
 * That is to say we supply one dwell time before and after every hop time like so: O=O=O=O=O
 * The number of dwell times must be equal to the number of inserted stops, plus one dwell for fromStop and one dwell
 * for toStop when they are specified.
 *
 * That is to say, a dwell time is supplied for each "anchor stop" referenced in the original trip pattern,
 * and one more for each stop in the stops list. For N dwells, N-1 hops are supplied to tie them all together.
 *
 * Inserting stops into some trips but not others on a pattern could create new patterns. Therefore we only allow
 * applying this modification to entire routes or patterns.
 */
public class Reroute extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(Reroute.class);

    /** Which routeIds should be rerouted. */
    public Set<String> routes;

    /** One or more example tripIds on each pattern that should be rerouted. */
    public Set<String> patterns;

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

    /*
      NOTE These variables will be changing while apply() is on the stack.
      They pass information between private functions.
      A single Modification instance must not be used by multiple threads.
    */
    private int insertBeginIndex;

    private int insertEndIndex;

    private int newPatternLength;

    @Override
    public String getType() {
        return "reroute";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        boolean onlyOneDefined = (routes != null) ^ (patterns != null);
        if (!onlyOneDefined) {
            warnings.add("Routes or patterns must be specified, but not both.");
        }
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
        int expectedDwells = stops.size();
        if (fromStop != null) {
            expectedDwells += 1;
        }
        if (toStop != null) {
            expectedDwells += 1;
        }
        if (dwellTimes == null) {
            dwellTimes = new int[0];
        }
        if (dwellTimes.length != expectedDwells) {
            warnings.add("You must supply one dwell time per new stop, plus one for fromStop and one for toStop when they are specified.");
        }
        if (hopTimes == null) {
            warnings.add("You must always supply some hop times.");
        } else if (hopTimes.length != expectedDwells - 1) {
            warnings.add("The number of hops must always be one less than the number of dwells.");
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
        if (patterns != null && originalTripPattern.containsNoTrips(patterns)) {
            // This TripPattern does not contain any of the example trips, so it is not rerouted.
            return originalTripPattern;
        }
        // The number of elements to copy from the original stops array before the new segment is spliced in.
        // That is, the first index in the output stops array that will contain a stop from the new segment.
        insertBeginIndex = -1;
        // The element of the original stops array at which copying resumes after the new segment is spliced in.
        insertEndIndex = -1;
        for (int s = 0; s < originalTripPattern.stops.length; s++) {
            if (originalTripPattern.stops[s] == intFromStop) {
                insertBeginIndex = s + 1;
            }
            if (originalTripPattern.stops[s] == intToStop) {
                insertEndIndex = s;
            }
        }
        if (intFromStop == -1) {
            // When no fromStop is supplied, the new segment is inserted at index 0 of the output pattern.
            insertBeginIndex = 0;
        }
        if (intToStop == -1) {
            // When no toStop is supplied, no more of the original pattern will be copied after inserting the new segment.
            insertEndIndex = originalTripPattern.stops.length;
        }
        if (insertBeginIndex == -1 || insertEndIndex == -1) {
            // We are missing either the beginning or the end of the insertion slice.
            // This TripPattern does not match.
            warnings.add("The specified fromStop and/or toStop could not be matched on pattern " + originalTripPattern);
            return originalTripPattern;
        }
        if (insertEndIndex < insertBeginIndex) {
            warnings.add("The end of the insertion region must be at or after its beginning.");
            return originalTripPattern;
        }

        /* NOTE this is using fields which share information among private function calls. Not threadsafe! */
        int nStopsToRemove = insertEndIndex - insertBeginIndex;
        int oldLength = originalTripPattern.stops.length;
        newPatternLength = (oldLength + intNewStops.size()) - nStopsToRemove;

        // First, reroute the pattern itself (the stops).
        TripPattern pattern = reroutePattern(originalTripPattern);

        // Then perform the same rerouting operation for every individual trip on this pattern.
        pattern.tripSchedules = originalTripPattern.tripSchedules.stream()
                .map(this::rerouteTripSchedule)
                .collect(Collectors.toList());

        return pattern;
    }

    /**
     * Copy the original pattern to a new one, inserting or leaving out stops as necessary.
     * This is copying the stops, PickDropTypes, etc. shared by the whole pattern.
     * Individual TripSchedules will be modified separately later.
     */
    private TripPattern reroutePattern (TripPattern originalTripPattern) {

        // Create a protective copy of this TripPattern whose arrays are shorter or longer as needed.
        TripPattern pattern = originalTripPattern.clone();
        pattern.stops = new int[newPatternLength];
        pattern.pickups = new PickDropType[newPatternLength];
        pattern.dropoffs = new PickDropType[newPatternLength];
        pattern.wheelchairAccessible = new BitSet(newPatternLength);

        // ss is the stop index within the source (original) pattern.
        // ts is the stop index within the target (new) pattern.
        // Iterate until the target pattern is full.
        for (int ss = 0, ts = 0; ts < newPatternLength; ss++, ts++) {
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
                // Skip over the stops in the original (source) trip pattern that are to be replaced.
                ss = insertEndIndex;
                // If the output pattern is full, we just spliced the new stops onto the end of the pattern.
                // Quit without copying any more from the source pattern.
                if (ts == newPatternLength) break;
            }
            // Copy one stop from the source to the target
            pattern.stops[ts] = originalTripPattern.stops[ss];
            pattern.pickups[ts] = originalTripPattern.pickups[ss];
            pattern.dropoffs[ts] = originalTripPattern.dropoffs[ss];
            pattern.wheelchairAccessible.set(ts, originalTripPattern.wheelchairAccessible.get(ss));
        }

        LOG.info("Old stop sequence: {}", originalTripPattern.stops);
        LOG.info("New stop sequence: {}", pattern.stops);
        LOG.info("Old stop IDs: {}", Arrays.stream(originalTripPattern.stops)
                .mapToObj(network.transitLayer.stopIdForIndex::get).collect(Collectors.toList()));
        LOG.info("New stop IDs: {}", Arrays.stream(pattern.stops)
                .mapToObj(network.transitLayer.stopIdForIndex::get).collect(Collectors.toList()));

        return pattern;
    }

    /**
     * TODO calculate all arrivals and departures zero-based, and then shift them at the end to maintain fixed-point stop.
     * The fixed-point stop should be different depending on whether we are splicing onto the beginning or end of the route.
     */
    private TripSchedule rerouteTripSchedule (TripSchedule originalSchedule) {

        // Make a protective copy of this TripSchedule, changing the length of its arrays as needed.
        TripSchedule schedule = originalSchedule.clone();
        schedule.arrivals = new int[newPatternLength];
        schedule.departures = new int[newPatternLength];

        // Track the departure time from the last stop saved in the output across iterations.
        // On the first stop there is no preceding hop, which leads to some conditionals below.
        int prevOutputDeparture = 0;

        // ss is the source stop position in the pattern
        // ts is the target stop position in the pattern
        // Iterate until we've filled all slots in the target pattern.
        for (int ss = 0, ts = 0; ts < newPatternLength; ss++, ts++) {

            // When we reach the right place in the source pattern, splice in the new schedule fragment.
            // When we're inserting in the middle of the pattern, consider that a dwell is supplied for the fromStop.
            if ((ss == insertBeginIndex - 1) || (ss == 0 && insertBeginIndex == 0)) {

                // Calculate hop time, dealing with the fact that at stop zero there is no preceding hop.
                int hopTime = originalSchedule.arrivals[ss];
                if (ss > 0) hopTime -= originalSchedule.departures[ss - 1];

                // There is always one more dwell than there are hops.
                // Consume that dwell before looping over the rest.
                schedule.arrivals[ts] = prevOutputDeparture + hopTime;
                schedule.departures[ts] = schedule.arrivals[ts] + dwellTimes[0];
                prevOutputDeparture = schedule.departures[ts];
                ts++; // One output slot has been filled.

                // There is always one less hop than dwell, but we iterate through the two in lock-step.
                for (int hop = 0, dwell = 1; dwell < dwellTimes.length; hop++, dwell++) {
                    schedule.arrivals[ts] = prevOutputDeparture + hopTimes[hop];
                    schedule.departures[ts] = schedule.arrivals[ts] + dwellTimes[dwell];
                    prevOutputDeparture = schedule.departures[ts];
                    ts++; // One output slot has been filled.
                }

                // Skip over the part of the source pattern that was replaced by the new schedule fragment.
                // We have used a dwell to replace the original dwell at toStop, so step up to the following hop.
                ss = insertEndIndex + 1;

                // If the output pattern is full, we just spliced the new stops onto the end of the pattern.
                // Do not copy any more information from the source pattern.
                if (ts == newPatternLength) break;

            }

            // Accumulate one ride and one dwell time from the source schedule into the target schedule.
            // Deal with the fact that at stop zero there is no preceding hop.
            int hopTime = originalSchedule.arrivals[ss];
            if (ss > 0) hopTime -= originalSchedule.departures[ss - 1];
            schedule.arrivals[ts] = prevOutputDeparture + hopTime;

            int dwellTime = originalSchedule.departures[ss] - originalSchedule.arrivals[ss];
            schedule.departures[ts] = schedule.arrivals[ts] + dwellTime;
            prevOutputDeparture = schedule.departures[ts];

        }

        LOG.info("Original arrivals:   {}", originalSchedule.arrivals);
        LOG.info("Original departures: {}", originalSchedule.departures);
        LOG.info("Modified arrivals:   {}", schedule.arrivals);
        LOG.info("Modified departures: {}", schedule.departures);
        return schedule;

    }

    @Override
    public boolean affectsStreetLayer() {
        return stops.stream().anyMatch(s -> s.id == null);
    }

    public int getSortOrder() { return 40; }


}
