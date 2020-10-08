package com.conveyal.r5.analyst.scenario;

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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
     * By default, all existing trips on this route will be removed.
     * When retainTripsOutsideFrequencyEntries is true, we will retain all existing trips that do not begin during the
     * time windows of newly created frequency entries. The reason we're looking at the start time of the trips is
     * because frequency entries are defined to specify the start and end time at the first stop.
     * When some entries define scheduled trips, those entries are considered to be zero-width and all existing trips
     * are "outside" them, so existing trips will never be rejected because of a scheduled entry when
     * retainTripsOutsideFrequencyEntries is true.
     */
    public boolean retainTripsOutsideFrequencyEntries = false;

    /**
     * A map containing all the frequency entries, keyed on the source trip ID of the frequency entries.
     * It's a multimap because more than one entry can referece the same trip.
     */
    private transient Multimap<String, PatternTimetable> entriesByTrip;

    private transient List<Service> servicesCopy;

    /** For logging the effects of the modification and reporting an error when the modification has no effect. */
    private int nTripSchedulesCreated = 0;

    private int nPatternsCleared = 0;

    /** As we scan over the patterns, this set is populated to keep track of which entries have been handled. */
    private Set<PatternTimetable> entriesMatched = new HashSet<>();

    @Override
    public boolean resolve (TransportNetwork network) {
        if (entries.isEmpty()) {
            errors.add("This modification should include at least one timetable/frequency entry.");
        }
        for (PatternTimetable entry : entries) {
            errors.addAll(entry.validate(-1));
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply(TransportNetwork network) {

        // Sort out all the service entries by which pattern they use as a template.
        entriesByTrip = HashMultimap.create();
        for (PatternTimetable entry : entries) {
            entriesByTrip.put(entry.sourceTrip, entry);
        }

        // Make a protective copy of the services that we can extend.
        servicesCopy = new ArrayList<>(network.transitLayer.services);
        network.transitLayer.services = servicesCopy;

        // Scan over all patterns, copying and modifying those on the given route, but leaving others untouched.
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processPattern)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // We may have created frequencies or schedules. Update the relevant summary fields at the network layer.
        network.transitLayer.hasFrequencies = network.transitLayer.hasSchedules = false;
        for (TripPattern tp : network.transitLayer.tripPatterns) {
            if (tp.hasFrequencies) network.transitLayer.hasFrequencies = true;
            if (tp.hasSchedules) network.transitLayer.hasSchedules = true;
        }

        // Check that every entry was applied and that this modification had the expected effect on the network.
        Set<PatternTimetable> unmatchedEntries = new HashSet<>(entries);
        unmatchedEntries.removeAll(entriesMatched);
        for (PatternTimetable entry : unmatchedEntries) {
            errors.add("Trip not found for ID " + entry.sourceTrip + ". Ensure a trip pattern has been selected in " +
                    "this modification.");
        }
        LOG.info("Cleared {} patterns, creating {} new trip schedules.", nPatternsCleared, nTripSchedulesCreated);

        return errors.size() > 0;
    }

    /**
     * @param originalSchedule the schedule to make a copy of.
     * @param firstDepartureTime the time of the departure from the first stop in seconds after midnight.
     * @param serviceCode the internal integer code for the service on which the cloned trip will run.
     * @return a clone of originalSchedule with the arrival and departure times shifted to match firstDepartureTime.
     */
    private TripSchedule shiftedCopy (final TripSchedule originalSchedule, int firstDepartureTime, int serviceCode) {
        // Clone the original trip and overwrite its service code.
        TripSchedule newSchedule = originalSchedule.clone();
        int nStops = originalSchedule.getNStops();
        newSchedule.arrivals = new int[nStops];
        newSchedule.departures = new int[nStops];
        newSchedule.serviceCode = serviceCode;
        // Adjust the arrival and departure times of the new trip to match the specified firstDepartureTime.
        // We eliminate any dwell at the first stop since it could unintentionally shift the rest of the trip significantly.
        int offset = originalSchedule.departures[0];
        newSchedule.arrivals[0] = firstDepartureTime;
        newSchedule.departures[0] = firstDepartureTime;
        for (int i = 1; i < nStops; i++) {
            newSchedule.arrivals[i] = originalSchedule.arrivals[i] - offset + firstDepartureTime;
            newSchedule.departures[i] = originalSchedule.departures[i] - offset + firstDepartureTime;
        }
        return newSchedule;
    }

    /**
     * Applied to each TripPattern in the TransitLayer in turn. Some will be returned unchanged, others copied and
     * changed.
     */
    private TripPattern processPattern(TripPattern originalPattern) {

        if (!originalPattern.routeId.equals(route)) {
            // This pattern is not on the route to be frequency-adjusted.
            return originalPattern;
        }

        // Record that this pattern's TripSchedules are being replaced for error reporting and debugging purposes.
        nPatternsCleared += 1;

        // This pattern is on the route to be frequency-adjusted.
        // Create a copy of the pattern to hold the filtered trips.
        TripPattern newPattern = originalPattern.clone();
        newPattern.tripSchedules = new ArrayList<>();
        newPattern.servicesActive = new BitSet();

        // These booleans will be updated as TripSchedules are copied or added.
        newPattern.hasFrequencies = false;
        newPattern.hasSchedules = false;

        // Retain modified clones of the TripSchedules for those trips mentioned in any PatternTimetable.
        // Drop all other TripSchedules, and then update the retained TripSchedules to be frequency entries. FIXME <-- comment is now wrong
        for (TripSchedule originalSchedule : originalPattern.tripSchedules) {

            // If this current TripSchedule's trip is mentioned in any PatternTimetables,
            // convert it into one or more new TripSchedules that represent new frequency or scheduled trips.
            // TODO this may create multiple entries with the same trip ID.
            for (PatternTimetable entry : entriesByTrip.get(originalSchedule.tripId)) {

                // Record that this entry has been matched for error reporting and debugging purposes.
                entriesMatched.add(entry);

                // Create one new service that reflects the days switched on in this PatternTimetable.
                // The newly created service's code will be the number of services already in the list.
                int serviceCode = servicesCopy.size();
                servicesCopy.add(AddTrips.createService(entry));
                newPattern.servicesActive.set(serviceCode);

                // TODO maybe don't enforce frequencies vs. schedules and just allow both. Then we can simply loop over a (possibly empty) array.
                if (entry.firstDepartures != null) {
                    // Create schedule trips (rather than frequency trips), one for each entry in firstDepartures.
                    for (int firstDepartureTime : entry.firstDepartures) {
                        TripSchedule newSchedule = shiftedCopy(originalSchedule, firstDepartureTime, serviceCode);
                        newSchedule.headwaySeconds = null;
                        newSchedule.startTimes = null;
                        newSchedule.endTimes = null;
                        newPattern.tripSchedules.add(newSchedule);
                        nTripSchedulesCreated += 1;
                    }
                    newPattern.hasSchedules = true;
                } else {
                    // Create frequency trips (rather than schedule trips).
                    // We create exactly one new TripSchedule for each PatternTimetable. Theoretically several
                    // PatternTimetables could be covered by a single TripSchedule by having more than one entry in the
                    // following fields, but then we'd have to sort out whether those PatternTimetables had service on
                    // the same day &c.
                    // R5 expects frequency TripSchedules to be zero-based.
                    TripSchedule newSchedule = shiftedCopy(originalSchedule, 0, serviceCode);
                    newSchedule.headwaySeconds = new int[]{entry.headwaySecs};
                    newSchedule.startTimes = new int[]{entry.startTime};
                    newSchedule.endTimes = new int[]{entry.endTime};
                    entry.applyPhasing(newSchedule);
                    newPattern.tripSchedules.add(newSchedule);
                    nTripSchedulesCreated += 1;
                    newPattern.hasFrequencies = true;
                }

            }

            // Whether or not the current TripSchedule's trip was mentioned in any PatternTimetable (and has therefore
            // been mutated and copied to the output as a new frequency or scheduled trip), we may or may not want to
            // retain that original trip in the output TripPattern.
            // By default (when retainTripsOutsideFrequencyEntries is false) we don't want to retain any of these
            // original trips, which is to say we don't copy them into the tripSchedules list of the copied pattern.
            // But if retainTripsOutsideFrequencyEntries is true, we want to retain those trips that are outside the
            // times ranges where the frequency PatternTimetables are active. Scheduled PatternTimetables are considered
            // to have a zero width, so all existing trips are considered to be outside their active time period.
            if (retainTripsOutsideFrequencyEntries) {
                // Copy all scheduled trips that are not entirely superseded by new frequency entries to the output pattern.
                Service reducedService = blackOutService(originalSchedule);
                if (reducedService != null) {
                    TripSchedule newSchedule = originalSchedule.clone();
                    // Assign the newly created reduced Service the next available int service code.
                    // If the service was not modified, this will re-add an existing service to the list but that should be harmless.
                    newSchedule.serviceCode = servicesCopy.size();
                    servicesCopy.add(reducedService);
                    newPattern.servicesActive.set(newSchedule.serviceCode);
                    newPattern.tripSchedules.add(newSchedule);
                    newPattern.hasSchedules = true;
                }
            }
        } // END for loop over all TripSchedules in this TripPattern.

        if (newPattern.tripSchedules.isEmpty()) {
            // None of the trips on this pattern appear in this Modification's PatternTimetable entries,
            // and none of the original trips were retained. Drop the pattern from the output network.
            return null;
        }

        // we need to sort scheduled trips as RaptorWorker assumes they are sorted
        if (newPattern.hasSchedules) {
            newPattern.tripSchedules.sort((ts1, ts2) -> ts1.departures[0] - ts2.departures[0]);
        }

        return newPattern;
    }

    /**
     * Given a TripSchedule with service running on a set of days s, return a new Service object that represents
     * service running on s - f, where f is the set of all days on which this modification's PatternTimetables
     * define a frequency service during which the supplied schedule departs from its first stop.
     * This new Service object expresses when the given TripSchedule would run if it was
     * prevented from running during any of these new frequency entries created by this modification.
     * @return a new Schedule for the given trip, considering the blackout period determined by the frequency
     *         PatternTimetables, or null if the tripSchedule will no longer be active at all.
     */
    private Service blackOutService(final TripSchedule schedule) {

        if (schedule.headwaySeconds != null) {
            errors.add("We do not currently support retaining existing frequency entries when adjusting timetables.");
            return null;
        }

        // Get the service on which the given TripSchedule is active.
        Service service = servicesCopy.get(schedule.serviceCode);

        // First, for all PatternTimetables that define frequency service over a time window during which the
        // supplied TripSchedule departs from its first stop, record the days of the week that frequency is active.
        EnumSet blackoutDays = EnumSet.noneOf(DayOfWeek.class);
        int tripStart = schedule.departures[0];
        for (PatternTimetable entry : entries) {
            // Skip over entries defining scheduled (i.e. not frequency) service, which are considered to be zero-width.
            if (entry.firstDepartures != null) continue;
            if (entry.startTime < tripStart && entry.endTime > tripStart) {
                blackoutDays.addAll(entry.activeDaysOfWeek());
            }
        }

        if (blackoutDays.isEmpty()) {
            // This will happen when this trip begins at a time outside all frequency PatternTimetables.
            // This includes the case where all PatternTimetables define scheduled (rather than frequency) service.
            // In these cases, the original trip is retained with its original Service intact.
            return service;
        }

        // TODO: verify that service IDs are not used anywhere in R5, because here we're recycling one.
        // TODO: This is creating a brand-new service for many retained trips. This is rather inefficient.
        Service newService = service.removeDays("SCENARIO_" + service.service_id, blackoutDays);
        return newService.hasAnyService() ? newService : null;
    }

    public int getSortOrder() { return 50; }

}
