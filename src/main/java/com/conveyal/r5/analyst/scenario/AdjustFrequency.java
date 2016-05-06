package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.analyst.scenario.AddTrips.PatternTimetable;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Adjust headways on a route. There should only be one such Modification per route.
 * It wipes all non-frequency trips on the route away and replaces them with the described frequency trips.
 * Each entry converts the specified trip is converted into a frequency trip with the given headway and schedule.
 * All other trips on the given route that do not appear in frequency entries are dropped.
 * This is to avoid accidentally leaving short-turn trips that aren't getting frequencies specified in the graph.
 * The sourceTrip is only used to derive the stop pattern and travel times. We do not guess existing frequencies.
 */
public class AdjustFrequency extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(AdjustFrequency.class);

    /** The route which is to have its frequency adjusted. */
    public String route;

    /**
     * The days and times of day at which the new frequency-based route will be running.
     * We re-use the model class from Reroute because they are so similar to AdjustFrequency entries,
     * and we'd like to reuse the code for creating new calendar entries.
     */
    public List<PatternTimetable> entries;

    /**
     * Should all existing trips on this route be removed (true, default), or should we retain trips that are not
     * during the time windows being converted to frequency (false)?
     */
    public boolean dropTripsOutsideTimePeriod = true;

    /**
     * A map containing all the frequency entries, keyed on the source trip ID of the frequency entries.
     *
     * It's a multimap because yoou can have more than entry per trip.
     */
    private transient Multimap<String, PatternTimetable> entriesByTrip;

    private transient List<Service> servicesCopy;

    @Override
    public String getType() {
        return "adjust-frequency";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        entriesByTrip = HashMultimap.create();
        for (PatternTimetable entry : entries) {
            entriesByTrip.put(entry.sourceTrip, entry);
        }
        // Protective copy that we can extend.
        servicesCopy = new ArrayList<>(network.transitLayer.services);
        network.transitLayer.services = servicesCopy;
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processPattern)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // we may have created frequencies or schedules
        network.transitLayer.hasFrequencies = network.transitLayer.hasSchedules = false;
        for (TripPattern tp : network.transitLayer.tripPatterns) {
            if (tp.hasFrequencies) network.transitLayer.hasFrequencies = true;
            if (tp.hasSchedules) network.transitLayer.hasSchedules = true;
        }

        return warnings.size() > 0;
    }

    private TripPattern processPattern(TripPattern originalPattern) {
        // Were any scheduled trips retained or created?
        boolean wereSchedulesRetainedOrCreated = false;

        if (!originalPattern.routeId.equals(route)) {
            // This pattern is not on the route to be frequency-adjusted.
            return originalPattern;
        }
        // This pattern is on the route to be frequency-converted.
        // Retain clones of the TripSchedules for those trips mentioned in any PatternTimetable.
        // Drop all other TripSchedules, and then update the retained TripSchedules to be frequency entries.
        TripPattern newPattern = originalPattern.clone();
        newPattern.tripSchedules = new ArrayList<>();
        newPattern.servicesActive = new BitSet();
        for (TripSchedule originalSchedule : originalPattern.tripSchedules) {
            // Grab all the frequency specification entries that reference this TripSchedule's tripId and create
            // one new TripSchedule for each of them. It would be theoretically possible to have a single trip schedule
            // represent several frequency entries by having more entries in headway seconds, but then we would have to
            // sort out whether they have service on the same day, &c., so we just create a new trip schedule for each
            // entry.
            for (PatternTimetable entry : entriesByTrip.get(originalSchedule.tripId)) {
                TripSchedule newSchedule = originalSchedule.clone();
                newSchedule.arrivals = originalSchedule.arrivals.clone();
                newSchedule.departures = originalSchedule.departures.clone();
                // Adjust the arrival and departure times of this trip to be zero-based, since that's what R5 expects.
                int offset = originalSchedule.arrivals[0];
                for (int i = 0; i < originalSchedule.getNStops(); i++) {
                    newSchedule.arrivals[i] = originalSchedule.arrivals[i] - offset;
                    newSchedule.departures[i] = originalSchedule.departures[i] - offset;
                }
                newSchedule.headwaySeconds = new int[]{entry.headwaySecs};
                newSchedule.startTimes = new int[]{entry.startTime};
                newSchedule.endTimes = new int[]{entry.endTime};
                // The newly created service's code will be the number of services already in the list.
                newSchedule.serviceCode = servicesCopy.size();
                servicesCopy.add(AddTrips.createService(entry));
                newPattern.servicesActive.set(newSchedule.serviceCode);
                newPattern.tripSchedules.add(newSchedule);
            }

            if (!dropTripsOutsideTimePeriod) {
                if (originalSchedule.headwaySeconds != null) {
                    warnings.add("We do not currently support retaining frequency entries when adjusting timetables.");
                } else {
                    // retain scheduled trips that do not overlap frequency entries
                    // these booleans determine on what days service on this trip schedule should be retained

                    boolean monday = true;
                    boolean tuesday = true;
                    boolean wednesday = true;
                    boolean thursday = true;
                    boolean friday = true;
                    boolean saturday = true;
                    boolean sunday = true;

                    int tripStart = originalSchedule.departures[0];

                    for (PatternTimetable timetable : entries) {
                        if (timetable.startTime < tripStart && timetable.endTime > tripStart) {
                            // this trip starts during this frequency entry's validity period, ditch it
                            // on the days when this frequency entry runs.
                            monday = monday && !timetable.monday;
                            tuesday = tuesday && !timetable.tuesday;
                            wednesday = wednesday && !timetable.wednesday;
                            thursday = thursday && !timetable.thursday;
                            friday = friday && !timetable.friday;
                            saturday = saturday && !timetable.saturday;
                            sunday = sunday && !timetable.sunday;
                        }
                    }

                    // TODO: this is creating a brand-new service for every single retained trip. this has got to be
                    // inefficient.
                    Service originalService = servicesCopy.get(originalSchedule.serviceCode);
                    Service newService = retainServiceOnDays(monday, tuesday, wednesday, thursday, friday, saturday, sunday, originalService);

                    // check if new Service has any service, either from calendar or calendar_dates
                    boolean hasAnyService = false;

                    if (newService.calendar != null &&
                            newService.calendar.monday == 1 ||
                            newService.calendar.tuesday == 1 ||
                            newService.calendar.wednesday == 1 ||
                            newService.calendar.thursday == 1 ||
                            newService.calendar.friday == 1||
                            newService.calendar.saturday == 1||
                            newService.calendar.sunday == 1
                            ) {
                        hasAnyService = true;
                    }

                    // exception type 1: added service
                    if (newService.calendar_dates.values().stream().anyMatch(cd -> cd.exception_type == 1)) {
                        hasAnyService = true;
                    }

                    if (hasAnyService) {
                        TripSchedule newSchedule = originalSchedule.clone();
                        newSchedule.serviceCode = servicesCopy.size();
                        servicesCopy.add(newService);
                        newPattern.servicesActive.set(newSchedule.serviceCode);
                        newPattern.tripSchedules.add(newSchedule);
                        wereSchedulesRetainedOrCreated = true;
                    }
                }
            }
        }
        if (newPattern.tripSchedules.isEmpty()) {
            // None of the trips on this pattern appear in this Modification's frequency entries. Drop the pattern.
            return null;
        }

        newPattern.hasFrequencies = true;
        newPattern.hasSchedules = wereSchedulesRetainedOrCreated;
        return newPattern;
    }

    /** Create a clone of the specified service with service removed on days that are not true */
    private Service retainServiceOnDays(boolean monday, boolean tuesday, boolean wednesday, boolean thursday,
                                        boolean friday, boolean saturday, boolean sunday, Service originalService) {
        // TODO I think service IDs are not used anywhere in R5.
        Service newService = new Service(originalService.service_id);

        // first copy the calendar, if present
        if (originalService.calendar != null) {
            newService.calendar = new Calendar();
            // make a new calendar that has service on the intersection of the days in the original calendar,
            // and the retained days
            newService.calendar.monday    = originalService.calendar.monday    == 1 && monday    ? 1 : 0;
            newService.calendar.tuesday   = originalService.calendar.tuesday   == 1 && tuesday   ? 1 : 0;
            newService.calendar.wednesday = originalService.calendar.wednesday == 1 && wednesday ? 1 : 0;
            newService.calendar.thursday  = originalService.calendar.thursday  == 1 && thursday  ? 1 : 0;
            newService.calendar.friday    = originalService.calendar.friday    == 1 && friday    ? 1 : 0;
            newService.calendar.saturday  = originalService.calendar.saturday  == 1 && saturday  ? 1 : 0;
            newService.calendar.sunday    = originalService.calendar.sunday    == 1 && sunday    ? 1 : 0;
            newService.calendar.start_date = originalService.calendar.start_date;
            newService.calendar.end_date = originalService.calendar.end_date;
        }

        // now copy exceptions
        originalService.calendar_dates.entrySet().forEach(e -> {
            LocalDate date = e.getKey();
            CalendarDate exception = e.getValue();

            if (exception.exception_type == 2) {
                // service removed, keep this exception, even if it's on a day of the week that's been removed it's
                // just redundant
                CalendarDate newException = exception.clone();
                newException.service = newService;
                newService.calendar_dates.put(date, newException);
            } else {
                // service added, retain iff it's on a retained day
                DayOfWeek dow = date.getDayOfWeek();
                if (monday && dow == DayOfWeek.MONDAY ||
                        tuesday && dow   == DayOfWeek.TUESDAY ||
                        wednesday && dow == DayOfWeek.WEDNESDAY ||
                        thursday && dow  == DayOfWeek.THURSDAY ||
                        friday && dow    == DayOfWeek.FRIDAY ||
                        saturday && dow  == DayOfWeek.SATURDAY ||
                        sunday && dow    == DayOfWeek.SUNDAY) {
                    // retain this exception
                    CalendarDate newException = exception.clone();
                    newException.service = newService;
                    newService.calendar_dates.put(date, newException);
                }
            }
        });

        return newService;
    }

    public int getSortOrder() { return 50; }

}
