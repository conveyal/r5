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
import java.util.stream.IntStream;

/**
 * Add a trip pattern.
 * May add new stops or reuse existing stops.
 * Can include a full timetable of trips or be a frequency-based pattern.
 */
public class AddTrips extends Modification {

    public static final long serialVersionUID = 1L;
    public static final Logger LOG = LoggerFactory.getLogger(AddTrips.class);

    /** A list of stops on the new trip pattern. These may be existing or completely new stops. */
    public List<StopSpec> stops;

    /** The timetables for this trip pattern */
    public Collection<PatternTimetable> frequencies;

    /** GTFS mode (route_type), see constants in com.conveyal.gtfs.model.Route */
    public int mode = Route.BUS;

    /** If set to true, create both the forward pattern and a derived backward pattern as a matching set. */
    public boolean bidirectional = true;

    @Override
    public String getType() {
        return "add-trips";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        for (PatternTimetable pt : frequencies) {
            if (pt.endTime <= pt.startTime) {
                warnings.add("End time is not later than start time.");
            }
            if (pt.headwaySecs <= 0) {
                warnings.add("Headway is not greater than zero.");
            }
        }
        for (StopSpec stop : stops) {
            // FIXME handle missing value (which is currently 0, a real stop index, rather than -1).
            // TODO add new stops, currently you can only reference existing ones.
            int stopIndex = network.transitLayer.indexForStopId.get(stop.stopId);
            if (stopIndex == 0) {
                warnings.add("Could not find a stop for GTFS ID " + stop.stopId);
            }
        }
        return warnings.size() > 0;
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
        if (bidirectional) {
            // Reverse the stopIds in place. Not sure how wise this is but it works.
            // Guava Lists.reverse would return a view / copy.
            Collections.reverse(stops);
            for (PatternTimetable ptt : frequencies) {
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
        TripPatternKey tripPatternKey = new TripPatternKey("SCENARIO_MODIFICATION");
        for (StopSpec stop : stops) {
            stopTime.stop_id = stop.stopId;
            tripPatternKey.addStopTime(stopTime, transitLayer.indexForStopId);
        }
        TripPattern pattern = new TripPattern(tripPatternKey);
        LOG.info("Created {}.", pattern);
        for (PatternTimetable timetable : frequencies) {
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

    /**
     * a class representing a minimal timetable
     * TODO rename to reflect usage in both AddTrips and AdjustFrequency.
     */
    public static class PatternTimetable implements Serializable {

        /** The trip ID from which to copy travel and dwell times. Used only in AdjustFrequency Modifications. */
        public String sourceTrip;

        /** The travel times in seconds between adjacent stops. Used only in AddTrips, not AdjustFrequency. */
        public int[] hopTimes;

        /** The time in seconds the vehicle waits at each stop. Used only in AddTrips, not AdjustFrequency. */
        public int[] dwellTimes;

        /** If true, create a frequency entry rather than a bundle of scheduled trips. */
        public boolean frequency;

        /** Start time of the first frequency-based trip in seconds since GTFS midnight. */
        public int startTime;

        /** End time for frequency-based trips in seconds since GTFS midnight. */
        public int endTime;

        /** Headway for frequency-based patterns. */
        public int headwaySecs;

        /** What days is this active on? */
        public boolean monday;
        public boolean tuesday;
        public boolean wednesday;
        public boolean thursday;
        public boolean friday;
        public boolean saturday;
        public boolean sunday;
    }

    /**
     * Creates a gtfs-lib Service object based on the information in the given PatternTimetable, which is usually
     * part of a Modification deserialized from JSON.
     */
    public static Service createService (PatternTimetable timetable) {
        // Create a calendar entry and service ID for this new trip pattern.
        Calendar calendar = new Calendar();
        // TODO move this logic to a function on Calendar in gtfs-lib
        calendar.monday    = timetable.monday    ? 1 : 0;
        calendar.tuesday   = timetable.tuesday   ? 1 : 0;
        calendar.wednesday = timetable.wednesday ? 1 : 0;
        calendar.thursday  = timetable.thursday  ? 1 : 0;
        calendar.friday    = timetable.friday    ? 1 : 0;
        calendar.saturday  = timetable.saturday  ? 1 : 0;
        calendar.sunday    = timetable.sunday    ? 1 : 0;
        StringBuilder nameBuilder = new StringBuilder("MOD-");
        nameBuilder.append(timetable.monday ? 'M' : 'x');
        nameBuilder.append(timetable.monday ? 'T' : 'x');
        nameBuilder.append(timetable.monday ? 'W' : 'x');
        nameBuilder.append(timetable.monday ? 'T' : 'x');
        nameBuilder.append(timetable.monday ? 'F' : 'x');
        nameBuilder.append(timetable.monday ? 'S' : 'x');
        nameBuilder.append(timetable.monday ? 'S' : 'x');
        Service service = new Service(nameBuilder.toString());
        // Very long date range from the year 1850 to 2200 should be sufficient.
        calendar.start_date = 18500101;
        calendar.end_date = 22000101;
        service.calendar = calendar;
        return service;
    }

    /**
     * Creates one or more R5 TripSchedule objects from a PatternTimetable object that was deserialized from JSON.
     * These represent either independent trips, or a frequency-based family of trips.
     * The supplied list of services will be extended with a new service,
     * whose code will be saved int the new TripSchedule.
     * Make sure the supplied list is a protective copy, not the one from the original TransportNetwork!
     */
    public List<TripSchedule> createSchedules (PatternTimetable timetable, List<Service> services) {
        int serviceCode = services.size(); // Code of a newly added Service will be the number of services in the list.
        services.add(createService(timetable));

        // Create a dummy GTFS Trip object so we can use the standard TripSchedule factory method.
        Trip trip = new Trip();
        trip.direction_id = 0;
        // Convert the supplied hop and dwell times (which are relative to adjacent entries) to arrival and departure
        // times (which are relative to the beginning of the trip or the beginning of the service day).
        int nStops = stops.size();
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
            schedules.add(TripSchedule.create(trip, arrivals, departures, IntStream.range(0, arrivals.length).toArray(), serviceCode));
        } else {
            // TODO if the trip is not a frequency trip should we be creating multiple schedules?
            // my understanding had been that when frequency == false, endTime and headway are ignored and the trip is
            // assumed to only run once.
            for (int t = timetable.startTime; t < timetable.endTime; t += timetable.headwaySecs) {
                int[] shiftedArrivals = new int[arrivals.length];
                int[] shiftedDepartures = new int[departures.length];
                for (int i = 0; i < nStops; i++) {
                    shiftedArrivals[i] = arrivals[i] + t;
                    shiftedDepartures[i] = departures[i] + t;
                }
                schedules.add(TripSchedule.create(trip, shiftedArrivals, shiftedDepartures, IntStream.range(0, arrivals.length).toArray(), serviceCode));
            }
        }
        return schedules;
    }
}
