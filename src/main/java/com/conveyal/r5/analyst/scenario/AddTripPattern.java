package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.model.json_serialization.BitSetDeserializer;
import com.conveyal.r5.model.json_serialization.BitSetSerializer;
import com.conveyal.r5.model.json_serialization.LineStringDeserializer;
import com.conveyal.r5.model.json_serialization.LineStringSerializer;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripPatternKey;
import com.conveyal.r5.transit.TripSchedule;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Add a trip pattern.
 * May add new stops or reuse existing stops.
 * Can include a full timetable of trips or be a frequency-based pattern.
 */
public class AddTripPattern extends Modification {

    public static final long serialVersionUID = 1L;
    public static final Logger LOG = LoggerFactory.getLogger(AddTripPattern.class);

    /** The name of this pattern */
    public String name;

    /** The geometry of this pattern */
    @JsonDeserialize(using = LineStringDeserializer.class)
    @JsonSerialize(using = LineStringSerializer.class)
    public LineString geometry;

    /** What coordinate indices should be stops */
    @JsonDeserialize(using = BitSetDeserializer.class)
    @JsonSerialize(using = BitSetSerializer.class)
    public BitSet stops;

    /** New way of using this modification: just supply existing stop IDs. */
    public List<String> stopIds;

    /** The timetables for this trip pattern */
    public Collection<PatternTimetable> timetables;

    /** used to store the indices of the temporary stops in the graph */
    public transient TemporaryStop[] temporaryStops;

    /** GTFS mode (route_type), see constants in com.conveyal.gtfs.model.Route */
    public int mode = Route.BUS;

    /** If set to true, create both the forward pattern and a derived backward pattern as a matching set. */
    public boolean generateReversePattern = true;

    private TripPatternKey tripPatternKey;

    @Override
    public String getType() {
        return "add-trip-pattern";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        boolean foundErrors = false;
        for (PatternTimetable pt : timetables) {
            if (pt.endTime <= pt.startTime) {
                warnings.add("End time is not later than start time.");
                foundErrors = true;
            }
            if (pt.headwaySecs <= 0) {
                warnings.add("Headway is not greater than zero.");
                foundErrors = true;
            }
        }
        for (String stopId : stopIds) {
            // FIXME handle missing value (which is currently 0, a real stop index, rather than -1).
            int stopIndex = network.transitLayer.indexForStopId.get(stopId);
            if (stopIndex == 0) {
                warnings.add("Could not find a stop for GTFS ID " + stopId);
                foundErrors = true;
            }
        }
        return foundErrors;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Protective copy of original transit layer so we can make modifications without affecting the original.
        TransitLayer transitLayer = network.transitLayer.clone();
        network.transitLayer = transitLayer;
        // We will be extending the list of TripPatterns, so make a protective copy of it.
        transitLayer.tripPatterns = new ArrayList<>(transitLayer.tripPatterns);
        // We will be creating a service for each supplied timetable, make a protective copy of the list of services.
        transitLayer.services = new ArrayList<>(transitLayer.services);
        generatePattern(transitLayer);
        if (generateReversePattern) {
            // Reverse the stopIds in place. Not sure how wise this is but it works.
            // Guava Lists.reverse would return a view / copy.
            Collections.reverse(stopIds);
            for (PatternTimetable ptt : timetables) {
                // Reverse all the pattern timetables in place. Not sure how wise this is but it works.
                // Amazingly, there is no Arrays.reverse() or Ints.reverse().
                TIntList hopList = new TIntArrayList();
                hopList.add(ptt.hopTimes);
                hopList.reverse();
                ptt.hopTimes = hopList.toArray();
                TIntList dwellList = new TIntArrayList();
                dwellList.add(ptt.dwellTimes);
                dwellList.reverse();
                ptt.dwellTimes = dwellList.toArray();
            }
            // TODO the transitlayer wouldn't really need to be passed around, it could be in a field.
            generatePattern(transitLayer);
        }
        // FIXME shouldn't rebuilding indexes happen automatically higher up?
        transitLayer.rebuildTransientIndexes();
        return false;
    }

    /**
     * This has been pulled out into a separate function so it can be called twice: once to generate the forward
     * pattern and once to generate the reverse pattern.
     * @param transitLayer a protective copy of a transit layer whose existing tripPatterns list will be extended.
     */
    private void generatePattern (TransitLayer transitLayer) {
        // Convert the supplied stop IDs into internal integer indexes for this TransportNetwork.
        // Pickup and drop off type default to 0, which means "scheduled".
        StopTime stopTime = new StopTime();
        tripPatternKey = new TripPatternKey("SCENARIO_MODIFICATION");
        for (String stopId : stopIds) {
            stopTime.stop_id = stopId;
            tripPatternKey.addStopTime(stopTime, transitLayer.indexForStopId);
        }
        TripPattern pattern = new TripPattern(tripPatternKey);
        LOG.info("Converted stop ID sequence {} to trip pattern {}.", stopIds, pattern);
        for (PatternTimetable timetable : timetables) {
            // CreateSchedules may create more than one if we're in non-frequency ("exact-times") mode.
            for (TripSchedule schedule : createSchedules(timetable, transitLayer.services)) {
                if (schedule == null) {
                    LOG.error("Failed to create a trip.");
                    continue;
                }
                pattern.addTrip(schedule);
                transitLayer.hasFrequencies |= timetable.frequency;
                transitLayer.hasSchedules |= !timetable.frequency;
            }
        }
        transitLayer.tripPatterns.add(pattern);
    }

    /** a class representing a minimal timetable */
    public static class PatternTimetable implements Serializable {
        /** hop times in seconds */
        public int[] hopTimes;

        /** dwell times in seconds */
        public int[] dwellTimes;

        /** is this a frequency entry? */
        public boolean frequency;

        /** start time (seconds since GTFS midnight) */
        public int startTime;

        /** end time for frequency-based trips (seconds since GTFS midnight) */
        public int endTime;

        /** headway for frequency-based patterns */
        public int headwaySecs;

        /** What days is this active on (starting with Monday at 0)? */
        @JsonDeserialize(using = BitSetDeserializer.class)
        @JsonSerialize(using = BitSetSerializer.class)
        public BitSet days;
    }

    /** A class representing a stop temporarily in the graph */
    public static class TemporaryStop {
        /** The indices of temporary stops are negative numbers to avoid clashes with the positive (vertex) indices of permanent stops. Note that code in RaptorWorkerData depends on these being negative. */
        private static AtomicInteger nextId = new AtomicInteger(-1);

        /** the index of this stop in the graph */
        public final int index;

        /** The latitude of this stop */
        public final double lat;

        /** The longitude of this stop */
        public final double lon;

        /** how this vertex is connected to the graph */
        public final Split split;

        public TemporaryStop (Coordinate c, StreetLayer streetLayer) {
            this(c.y, c.x, streetLayer);
        }

        public TemporaryStop (double lat, double lon, StreetLayer streetLayer) {
            this.lat = lat;
            this.lon = lon;
            this.index = nextId.decrementAndGet();
            this.split = streetLayer.findSplit(this.lat, this.lon, 200);
            if (this.split == null) {
                LOG.warn("Temporary stop unlinked: {}", this);
            }
        }

        public String toString () {
            return "Temporary stop at " + this.lat + ", " + this.lon;
        }
    }

    /**
     * Creates one or more R5 TripSchedule objects from a PatternTimetable object that was deserialized from JSON.
     * These represent either independent trips, or a frequency-based family of trips.
     * The supplied list of services will be extended with a new service,
     * whose code will be saved int the new TripSchedule.
     * Make sure the supplied list is a protective copy, not the one from the original TransportNetwork!
     */
    public List<TripSchedule> createSchedules (PatternTimetable timetable, List<Service> services) {
        // Create a calendar entry and service ID for this new trip pattern.
        // If no day information is supplied, make the pattern active every day of the week.
        BitSet days = timetable.days;
        if (days == null) {
            days = new BitSet();
            days.set(0, 7, true);
        }
        Calendar calendar = new Calendar();
        // TODO move this logic to a function on Calendar in gtfs-lib
        calendar.monday    = days.get(0) ? 1 : 0;
        calendar.tuesday   = days.get(1) ? 1 : 0;
        calendar.wednesday = days.get(2) ? 1 : 0;
        calendar.thursday  = days.get(3) ? 1 : 0;
        calendar.friday    = days.get(4) ? 1 : 0;
        calendar.saturday  = days.get(5) ? 1 : 0;
        calendar.sunday    = days.get(6) ? 1 : 0;
        StringBuilder nameBuilder = new StringBuilder("MOD-");
        for (int i = 0; i < 7; i++) {
            boolean active = days.get(i);
            nameBuilder.append(active ? 1 : 0);
        }
        // Very long date range from the year 1850 to 2200 should be sufficient.
        calendar.start_date = 18500101;
        calendar.end_date = 22000101;
        Service service = new Service(nameBuilder.toString());
        service.calendar = calendar;
        int serviceCode = services.size();
        services.add(service);

        // Create a dummy GTFS Trip object so we can use the standard TripSchedule factory method.
        Trip trip = new Trip();
        trip.direction_id = 0;
        // Convert the supplied hop and dwell times (which are relative to adjacent entries) to arrival and departure
        // times (which are relative to the beginning of the trip or the beginning of the service day).
        int nStops = stopIds.size();
        int[] arrivals = new int[nStops];
        int[] departures = new int[nStops];
        for (int s = 0, t = 0; s < nStops; s++) {
            arrivals[s] = t;
            if (s < timetable.dwellTimes.length) {
                t += timetable.dwellTimes[s];
            }
            departures[s] = t;
            if (s < timetable.hopTimes.length) {
                t += timetable.hopTimes[s];
            }
        }
        List<TripSchedule> schedules = new ArrayList<>();
        if (timetable.frequency) {
            Frequency freq = new Frequency();
            freq.start_time = timetable.startTime;
            freq.end_time = timetable.endTime;
            freq.headway_secs = timetable.headwaySecs;
            trip.frequencies = Lists.newArrayList(freq);
            schedules.add(TripSchedule.create(trip, arrivals, departures, serviceCode));
        } else {
            for (int t = timetable.startTime; t < timetable.endTime; t += timetable.headwaySecs) {
                int[] shiftedArrivals = new int[arrivals.length];
                int[] shiftedDepartures = new int[departures.length];
                for (int i = 0; i < nStops; i++) {
                    shiftedArrivals[i] = arrivals[i] + t;
                    shiftedDepartures[i] = departures[i] + t;
                }
                schedules.add(TripSchedule.create(trip, shiftedArrivals, shiftedDepartures, serviceCode));
            }
        }
        return schedules;
    }
}
