package com.conveyal.r5.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * FilteredPatterns correspond to a single specific TripPattern, indicating all the trips running on a particular day.
 * TripPatterns contain all the trips on a route that follow the same stop sequence. This often includes trips on
 * different days of the week or special schedules where vehicles travel faster or slower. By filtering down to only
 * those trips running on a particular day (a particular set of service codes), we usually get a smaller set of trips
 * with no overtaking, which enables certain optimizations and is more efficient for routing.
 */
public class FilteredPattern {

    private static Logger LOG = LoggerFactory.getLogger(FilteredPattern.class);

    /**
     * Schedule-based (i.e. not frequency-based) trips running in a particular set of GTFS services, sorted in
     * ascending order by time of departure from first stop
     */
    public List<TripSchedule> runningScheduledTrips = new ArrayList<>();

    /** Frequency-based trips active in a particular set of GTFS services */
    public List<TripSchedule> runningFrequencyTrips = new ArrayList<>();

    /** If no active schedule-based trip of this filtered pattern overtakes another. */
    public boolean noScheduledOvertaking;

    /**
     * Filter the trips in a source TripPattern, excluding trips not active in the supplied set of services, and
     * dividing them into separate scheduled and frequency trip lists. Check the runningScheduledTrips for overtaking.
     */
    public FilteredPattern (TripPattern source, BitSet servicesActive) {
        for (TripSchedule schedule : source.tripSchedules) {
            if (servicesActive.get(schedule.serviceCode)) {
                if (schedule.headwaySeconds == null) {
                    runningScheduledTrips.add(schedule);
                } else {
                    runningFrequencyTrips.add(schedule);
                }
            }
        }
        // Check whether any running trip on this pattern overtakes another
        noScheduledOvertaking = true;
        for (int i = 0; i < runningScheduledTrips.size() - 1; i++) {
            if (overtakes(runningScheduledTrips.get(i), runningScheduledTrips.get(i + 1))) {
                noScheduledOvertaking = false;
                LOG.warn("Overtaking: route {} pattern {}", source.routeId, source.originalId);
                break;
            }
        }
    }

    private static boolean overtakes (TripSchedule a, TripSchedule b) {
        for (int s = 0; s < a.departures.length; s++) {
            if (a.departures[s] > b.departures[s]) return true;
        }
        return false;
    }

}
