package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.Lists;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;

/**
 * Create a new trip pattern and add some trips to that pattern.
 * The pattern may create new stops or reuse existing ones from the base network.
 * The newly created trips are true frequency trips, not scheduled or exact_times frequencies.
 */
public class AddTrips extends Modification {

    public static final long serialVersionUID = 1L;
    public static final Logger LOG = LoggerFactory.getLogger(AddTrips.class);

    /** A list of stops on the new trip pattern. These may be existing or completely new stops. */
    public List<StopSpec> stops;

    /**
     * The timetables for this trip pattern. Note that these may be out of temporal order, it's up to the
     * consumer to get trips in order as required by the routing algorithm.
     */
    public Collection<PatternTimetable> frequencies;

    /** GTFS mode (route_type), see constants in com.conveyal.gtfs.model.Route */
    public int mode = Route.BUS;

    /** If set to true, create both the forward pattern and a derived backward pattern as a matching set. */
    public boolean bidirectional = true;

    /** A list of the internal integer IDs for the existing or newly created stops. */
    private TIntList intStopIds;

    /** Set a direction ID for this modification */
    public int directionId = 0;

    /** unique ID for transitive */
    private String routeId = UUID.randomUUID().toString();

    public String color = "4444FF";

    private int routeIndex;

    @Override
    public boolean resolve (TransportNetwork network) {
        if (stops == null || stops.size() < 2) {
            errors.add("You must provide at least two stops.");
        } else {
            if (frequencies.isEmpty()) {
                errors.add("This modification should include at least one timetable/frequency entry.");
            }
            for (PatternTimetable entry : frequencies) {
                errors.addAll(entry.validate(stops.size()));
            }
            intStopIds = findOrCreateStops(stops, network);
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        // We will be extending the list of TripPatterns, so make a protective copy of it.
        transitLayer.tripPatterns = new ArrayList<>(transitLayer.tripPatterns);
        // We will be creating a service for each supplied timetable, make a protective copy of the list of services.
        transitLayer.services = new ArrayList<>(transitLayer.services);

        // TODO lots more to fill in here, need to have a way to just specify all needed info in scenario editor.
        RouteInfo info = new RouteInfo();
        info.route_short_name = this.comment;
        info.route_long_name = this.comment;
        info.route_id = this.routeId;
        info.route_type = this.mode;
        info.color = this.color;
        this.routeIndex = transitLayer.routes.size();
        // No protective copy is made here because TransportNetwork.scenarioCopy already deep-copies certain collections.
        transitLayer.routes.add(info);

        if (this.directionId != 0 && this.directionId != 1) {
            throw new IllegalArgumentException("Direction must be 0/1");
        }

        generatePattern(transitLayer, this.directionId);
        if (bidirectional) {
            // We want to call generatePattern again, but with all stops and stoptimes reversed.
            // Reverse the intStopIds in place. The string stopIds should not be used anymore at this point.
            intStopIds.reverse();
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
            generatePattern(transitLayer, this.directionId == 0 ? 1 : 0); // other direction ID for bidirection route
        }
        return false;
    }

    /**
     * This has been pulled out into a separate function so it can be called twice: once to generate the forward
     * pattern and once to generate the reverse pattern.
     * @param transitLayer a protective copy of a transit layer whose existing tripPatterns list will be extended.
     * @param directionId should be 0 in one direction and 1 in the opposite direction.
     */
    private void generatePattern (TransitLayer transitLayer, int directionId) {
        TripPattern pattern = new TripPattern(intStopIds);
        LOG.info("Created {}.", pattern);
        for (PatternTimetable timetable : frequencies) {
            createSchedules(timetable, directionId, transitLayer.services).forEach(pattern::addTrip);
            if (timetable.firstDepartures == null) transitLayer.hasFrequencies = true;
            else transitLayer.hasSchedules = true;
        }
        // Ensure that generated trips are ordered as expected by R5, in case firstDepartures was not ordered.
        // Validation in resolve() ensures there are at least two stops, so at least one departure per trip.
        pattern.tripSchedules.sort(Comparator.comparing(ts -> ts.departures[0]));
        pattern.routeIndex = this.routeIndex;
        pattern.routeId = this.routeId;
        // We use the directionId method parameter rather than this.directionId. This allows two patterns to be created with opposite directionIds when bidirectional=true.
        pattern.directionId = directionId;

        transitLayer.tripPatterns.add(pattern);
    }

    /**
     * A class representing a timetable from the incoming modification deserialized from JSON.
     * TODO rename to reflect usage in both AddTrips and AdjustFrequency.
     */
    public static class PatternTimetable implements Serializable {
        public static final long serialVersionUID = 1L;

        /** The trip ID from which to copy travel and dwell times. Used only in AdjustFrequency Modifications. */
        public String sourceTrip;

        /** The travel times in seconds between adjacent stops. Used only in AddTrips, not AdjustFrequency. */
        public int[] hopTimes;

        /** The time in seconds the vehicle waits at each stop. Used only in AddTrips, not AdjustFrequency. */
        public int[] dwellTimes;

        /** Start time of the first frequency-based trip in seconds since GTFS midnight. */
        public int startTime = -1;

        /** End time for frequency-based trips in seconds since GTFS midnight. */
        public int endTime = -1;

        /** Headway for frequency-based patterns. */
        public int headwaySecs = -1;

        /**
         * Times in seconds after midnight when trips will begin. If this field is non-null the newly added
         * trips will be scheduled trips rather than frequency-based, and you must not provide the startTime,
         * endTime, or headwaySecs fields. These departure times are not necessarily sorted in ascending temporal
         * order, it's up to the modification application process to sort the resulting trip objects that are
         * generated, as required for routing.
         */
        public int[] firstDepartures;

        /** What days is this active on? */
        public boolean monday;
        public boolean tuesday;
        public boolean wednesday;
        public boolean thursday;
        public boolean friday;
        public boolean saturday;
        public boolean sunday;

        /** If non-null, phase this timetable from another timetable */
        public String phaseFromTimetable;

        /** What stop ID to perform phasing at in this trip pattern */
        public String phaseAtStop;

        /** What stop ID to perform phasing at in the phaseFrom trip pattern */
        public String phaseFromStop;

        /** Phase in seconds */
        public int phaseSeconds;

        /**
         * ID for this frequency entry, so that it can be referred to for phasing purposes. May be left null if
         * there is no phasing from this entry.
         */
        public String entryId;

        protected EnumSet<DayOfWeek> activeDaysOfWeek() {
            EnumSet days = EnumSet.noneOf(DayOfWeek.class);
            if (monday)    days.add(MONDAY);
            if (tuesday)   days.add(TUESDAY);
            if (wednesday) days.add(WEDNESDAY);
            if (thursday)  days.add(THURSDAY);
            if (friday)    days.add(FRIDAY);
            if (saturday)  days.add(SATURDAY);
            if (sunday)    days.add(SUNDAY);
            return days;
        }

        /**
         * Sanity and range check the contents of this entry, considering the fact that PatternTimetables may be used
         * to create either frequency or scheduled trips, and they may or may not include hop and dwell times depending
         * on whether a new pattern is being created.
         * @param nStops the number of stops in the pattern to be generated, or -1 if a new pattern is not being
         *               generated and only schedule / frequency fields are to be validated, not hops and dwell times.
         * @return a list of Strings, one for each warning generated.
         */
        protected List<String> validate (int nStops) {
            List<String> warnings = new ArrayList<>();
            if (headwaySecs >= 0 || startTime >= 0 || endTime >= 0) {
                // This entry is expected to be in frequency mode.
                if (headwaySecs < 0 || startTime < 0 || endTime < 0) {
                    warnings.add("You must specify headwaySecs, startTime, and endTime together to create frequency trips.");
                }
                if (headwaySecs <= 0) {
                    warnings.add("Headway is not greater than zero.");
                }
                if (endTime <= startTime) {
                    warnings.add("End time is not later than start time.");
                }
                if (firstDepartures != null) {
                    warnings.add("You cannot specify firstDepartures (which creates scheduled trips) along with headwaySecs, startTime, or endTime (which create frequency trips");
                }
            } else {
                // This entry is expected to be in scheduled (specific departure times) mode.
                if (firstDepartures == null) {
                    warnings.add("You must specify either firstDepartures (which creates scheduled trips) or all of headwaySecs, startTime, and endTime (which create frequency trips");
                } else if (phaseFromTimetable != null || phaseFromStop != null || phaseSeconds != 0 || phaseAtStop != null) {
                    warnings.add("You cannot specify phasing information in a scheduled trip.");
                }
            }

            if (phaseFromTimetable != null || phaseFromStop != null || phaseAtStop != null) {
                if (phaseFromTimetable == null || phaseFromStop == null || phaseAtStop == null) {
                    warnings.add("If one of phaseFromTimetable, phaseFromStop, phaseAtStop, or phaseSeconds is specified, all must be");
                }

                if (phaseSeconds < 0) {
                    warnings.add("Negative phasing not supported.");
                }
            }

            if (nStops >= 0) {
                // Validate hops and dwell times.
                if (nStops < 2) {
                    warnings.add("You must specify at least two stops when creating new trips");
                }
                if (dwellTimes == null || dwellTimes.length != nStops) {
                    warnings.add("The number of dwell times must be equal to the number of stops");
                }
                if (hopTimes == null || hopTimes.length != nStops - 1) {
                    warnings.add("The number of hop times must be one less than the number of stops");
                }
            }
            return warnings;
        }

        /**
         * If this PatternTimetable contains phasing information, apply it to the given TripSchedule destructively. Also
         * set the frequency entry ID.
         */
        public void applyPhasing(TripSchedule sched) {
            if (phaseFromTimetable != null) {
                sched.phaseFromId = new String[]{phaseFromTimetable};
                sched.phaseFromStop = new String[]{phaseFromStop};
                sched.phaseAtStop = new String[]{phaseAtStop};
                sched.phaseSeconds = new int[]{phaseSeconds};
            }

            // Generate an entry ID if none is specified.
            if (firstDepartures == null) {
                // not a schedule based trip, there is a frequency entry ID.
                // This won't work if we ever generate trips with multiple requency entries.
                sched.frequencyEntryIds = new String[]{entryId != null ? entryId : UUID.randomUUID().toString()};
            }
        }
    }

    /**
     * Creates a gtfs-lib Service object based on the information in the given PatternTimetable, which is usually
     * part of a Modification deserialized from JSON.
     */
    public static Service createService (PatternTimetable timetable) {
        // Create a calendar entry and service ID for this new trip pattern.
        Calendar calendar = new Calendar();
        // TODO move this logic to a function on Calendar in gtfs-lib, e.g. calendar.setFromDays(timetable.activeDaysOfWeek())
        calendar.monday    = timetable.monday    ? 1 : 0;
        calendar.tuesday   = timetable.tuesday   ? 1 : 0;
        calendar.wednesday = timetable.wednesday ? 1 : 0;
        calendar.thursday  = timetable.thursday  ? 1 : 0;
        calendar.friday    = timetable.friday    ? 1 : 0;
        calendar.saturday  = timetable.saturday  ? 1 : 0;
        calendar.sunday    = timetable.sunday    ? 1 : 0;
        StringBuilder nameBuilder = new StringBuilder("MOD-");
        nameBuilder.append(timetable.monday     ? 'M' : 'x');
        nameBuilder.append(timetable.tuesday    ? 'T' : 'x');
        nameBuilder.append(timetable.wednesday  ? 'W' : 'x');
        nameBuilder.append(timetable.thursday   ? 'T' : 'x');
        nameBuilder.append(timetable.friday     ? 'F' : 'x');
        nameBuilder.append(timetable.saturday   ? 'S' : 'x');
        nameBuilder.append(timetable.sunday     ? 'S' : 'x');
        Service service = new Service(nameBuilder.toString());
        // Very long date range from the year 1850 to 2200 should be sufficient.
        calendar.start_date = 18500101;
        calendar.end_date = 22000101;
        service.calendar = calendar;
        return service;
    }

    /**
     * Creates an R5 TripSchedule object from a PatternTimetable object that was deserialized from JSON.
     * These represent a truly frequency-based family of trips (non-exact-times in GTFS parlance).
     * The supplied list of services will be extended with a new service,
     * whose code will be saved in the new TripSchedule.
     * Make sure the supplied service list is a protective copy, not the one from the original TransportNetwork!
     */
    public List<TripSchedule> createSchedules (PatternTimetable timetable, int directionId, List<Service> services) {

        // The code for a newly added Service will be the number of services already in the list.
        int serviceCode = services.size();
        services.add(createService(timetable));

        // Create a dummy GTFS Trip object so we can use the standard TripSchedule factory method.
        Trip trip = new Trip();
        trip.direction_id = directionId;

        // Convert the supplied hop and dwell times (which are relative to adjacent entries) to arrival and departure
        // times (which are relative to the beginning of the trip or the beginning of the service day).
        // Note that frequency here means "pure" unscheduled frequencies handled by Monte Carlo; firstDepartures, like
        // exact_times in GTFS, are just a compact representation of exactly scheduled trips.
        final boolean frequency = timetable.firstDepartures == null;

        // if we are making one frequency trip, use 0 as its departure time
        int[] firstDepartures = frequency ? new int[] { 0 } : timetable.firstDepartures;

        return IntStream.of(firstDepartures).mapToObj(firstDeparture -> {
            int nStops = stops.size();
            int[] arrivals = new int[nStops];
            int[] departures = new int[nStops];
            for (int s = 0, t = firstDeparture; s < nStops; s++) {
                arrivals[s] = t;
                t += timetable.dwellTimes[s];
                departures[s] = t;
                if (s < timetable.hopTimes.length) {
                    t += timetable.hopTimes[s];
                }
            }
            Collection<Frequency> frequencies;
            if (frequency) {
                // Create an R5 frequency entry that will be attached to the new TripSchedule
                Frequency freq = new Frequency();
                freq.start_time = timetable.startTime;
                freq.end_time = timetable.endTime;
                freq.headway_secs = timetable.headwaySecs;
                frequencies = Lists.newArrayList(freq);
            } else {
                frequencies = Collections.emptyList();
            }
            TripSchedule sched = TripSchedule.create(
                    trip,
                    arrivals,
                    departures,
                    frequencies,
                    IntStream.range(0, arrivals.length).toArray(),
                    serviceCode
            );
            if (frequency) {
                timetable.applyPhasing(sched);
            }
            return sched;
        }).collect(Collectors.toList());
    }

    @Override
    public boolean affectsStreetLayer() {
        return stops.stream().anyMatch(s -> s.id == null);
    }

    public int getSortOrder() { return 70; }

}
