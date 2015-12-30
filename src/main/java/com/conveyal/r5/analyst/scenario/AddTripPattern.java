package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
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
        for (PatternTimetable timetable : timetables) {
            TripSchedule schedule = createSchedule(timetable);
            if (schedule != null) {
                pattern.addTrip(schedule);
            } else {
                LOG.error("could not create a trip");
                return originalTransitLayer;
            }
        }
        TransitLayer transitLayer = originalTransitLayer.clone();
        transitLayer.tripPatterns = new ArrayList<>(transitLayer.tripPatterns);
        transitLayer.tripPatterns.add(pattern);
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
     * This represents either a single trip, or a frequency-based family of trips.
     */
    public TripSchedule createSchedule (PatternTimetable timetable) {
        // Create a dummy GTFS Trip object so we can use the standard TripSchedule factory method.
        Trip trip = new Trip();
        // Convert the supplied hop and dwell times (which are relative to adjacent entries) to arrival and departure
        // times (which are relative to the beginning of the trip or the beginning of the service day).
        int nStops = stopIds.size();
        int t = 0;
        int[] arrivals = new int[nStops];
        int[] departures = new int[nStops];
        for (int s = 0; s < nStops; s++) {
            arrivals[s] = t;
            if (s < timetable.dwellTimes.length) {
                t += timetable.dwellTimes[s];
            }
            departures[s] = t;
            if (s < timetable.hopTimes.length) {
                t += timetable.hopTimes[s];
            }
        }
        if (timetable.frequency) {
            Frequency freq = new Frequency();
            freq.start_time = timetable.startTime;
            freq.end_time = timetable.endTime;
            freq.headway_secs = timetable.headwaySecs;
            trip.frequencies = Lists.newArrayList(freq);
            TripSchedule schedule = TripSchedule.create(trip, arrivals, departures, 01234);
            return schedule;
        } else {
            throw new UnsupportedOperationException();
        }
    }
}
