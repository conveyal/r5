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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Add a trip pattern */
public class AddTripPattern extends TransitLayerModification {

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

    /** Create temporary stops associated with the given graph. Note that a given AddTripPattern can be associated only with a single graph. */
    public void materialize (TransportNetwork tnet) {

        temporaryStops = new TemporaryStop[stops.cardinality()];

        int stop = 0;
        for (int i = stops.nextSetBit(0); i >= 0; i = stops.nextSetBit(i + 1)) {
            temporaryStops[stop++] = new TemporaryStop(geometry.getCoordinateN(i), tnet.streetLayer);
        }
    }

    @Override
    public String getType() {
        return "add-trip-pattern";
    }

    @Override
    protected TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        // Convert the supplied stop IDs into internal integer indexes for this TransportNetwork.
        TripPatternKey key = new TripPatternKey("SCENARIO_MODIFICATION");
        StopTime stopTime = new StopTime();
        for (String stopId : stopIds) {
            // Pickup and drop off type default to 0, which means "scheduled".
            // FIXME handle missing value, report unmatched stops
            stopTime.stop_id = stopId;
            key.addStopTime(stopTime, originalTransitLayer.indexForStopId);
        }
        TripPattern pattern = new TripPattern(key);
        LOG.info("Converted stop ID sequence {} to trip pattern {}.", stopIds, pattern);
        // Protective copy of original transit layer so we can make non-destructive modifications.
        TransitLayer transitLayer = originalTransitLayer.clone();
        // We will be creating a service for each supplied timetable, make a protective copy.
        List<Service> augmentedServices = new ArrayList<>(originalTransitLayer.services);
        for (PatternTimetable timetable : timetables) {
            for (TripSchedule schedule : createSchedules(timetable, augmentedServices)) {
                if (schedule == null) {
                    LOG.error("Failed to create a trip.");
                    continue;
                }
                pattern.addTrip(schedule);
                transitLayer.hasFrequencies |= timetable.frequency;
                transitLayer.hasSchedules |= !timetable.frequency;
            }
        }
        transitLayer.tripPatterns = new ArrayList<>(originalTransitLayer.tripPatterns);
        transitLayer.tripPatterns.add(pattern);
        transitLayer.services = augmentedServices;
        // FIXME shouldn't rebuilding indexes happen automatically higher up?
        transitLayer.rebuildTransientIndexes();
        return transitLayer;
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
     * Creates an internal R5 TripSchedule object from a PatternTimetable object that was deserialized from JSON.
     * This represents either a single trip, or a frequency-based family of trips. The supplied list of services will
     * be extended with a new service, whose code will be saved int the new TripSchedule. Make sure the supplied list is
     * a protective copy of the one from the original TransportNetwork!
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
