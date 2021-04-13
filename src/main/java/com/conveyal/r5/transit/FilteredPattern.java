package com.conveyal.r5.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;


public class FilteredPattern {

    private static Logger LOG = LoggerFactory.getLogger(FilteredPattern.class);

    /** Schedule-based (i.e. not frequency-based) trips running in a particular set of GTFS services */
    public List<TripSchedule> runningScheduledTrips = new ArrayList<>();

    /** Frequency-based trips active in a particular set of GTFS services */
    public List<TripSchedule> runningFrequencyTrips = new ArrayList<>();

    /** If no active schedule-based trip of this filtered pattern overtakes another. */
    public boolean noScheduledOvertaking;

    /**
     * Copy a TripPattern, ignoring fields not used in Raptor routing. The source pattern's trip schedules are
     * filtered to exclude trips not active in the supplied set of services, then divided into separate
     * scheduled and frequency trip lists. Finally, check the runningScheduledTrips for overtaking.
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
