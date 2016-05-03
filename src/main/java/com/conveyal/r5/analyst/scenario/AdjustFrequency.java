package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.analyst.scenario.AddTrips.PatternTimetable;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.eclipse.jetty.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return warnings.size() > 0;
    }

    private TripPattern processPattern(TripPattern originalPattern) {
        if (!originalPattern.routeId.equals(route)) {
            // This pattern is not on the route to be frequency-adjusted.
            return originalPattern;
        }
        // Retain clones of the TripSchedules for those trips, dropping all others, and then update the retained
        // TripSchedules to be frequency entries.
        TripPattern newPattern = originalPattern.clone();
        newPattern.tripSchedules = new ArrayList<>();
        newPattern.servicesActive = new BitSet();
        for (TripSchedule originalSchedule : originalPattern.tripSchedules) {
            for (PatternTimetable entry : entriesByTrip.get(originalSchedule.tripId)) {
                TripSchedule newSchedule = originalSchedule.clone();

                // It would be theoretically possible to have a single trip schedule represent several
                // frequency entries by having more entries in headway seconds, but then we would have to sort out
                // whether they have service on the same day, &c., so we just create a new trip schedule for each
                // entry
                newSchedule.headwaySeconds = new int[]{entry.headwaySecs};
                newSchedule.startTimes = new int[]{entry.startTime};
                newSchedule.endTimes = new int[]{entry.endTime};
                // New service's code will be the number of services already in the list.
                newSchedule.serviceCode = servicesCopy.size();
                servicesCopy.add(AddTrips.createService(entry));
                newPattern.servicesActive.set(newSchedule.serviceCode);
                newPattern.tripSchedules.add(newSchedule);
            }
        }
        if (newPattern.tripSchedules.isEmpty()) {
            // None of the trips on this pattern appear in this Modification's frequency entries. Drop the pattern.
            return null;
        }
        newPattern.hasFrequencies = true;
        newPattern.hasSchedules = false;
        return newPattern;
    }

}
