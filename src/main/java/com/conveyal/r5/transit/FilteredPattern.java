package com.conveyal.r5.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;


public class FilteredPattern extends TripPattern {

    private static Logger LOG = LoggerFactory.getLogger(FilteredPattern.class);

    /** Schedule-based (i.e. not frequency-based) trips running in a particular set of GTFS services */
    public List<TripSchedule> runningScheduledTrips = new ArrayList<>();

    /** Frequency-based trips active in a particular set of GTFS services */
    public List<TripSchedule> runningFrequencyTrips = new ArrayList<>();

    /** If no active schedule-based trip of this pattern overtakes another. */
    public boolean noScheduledOvertaking;

    /**
     * Copy a TripPattern, ignoring fields not used in Raptor routing. The source pattern's trip schedules are
     * filtered to exclude trips not active in the supplied set of services, then divided into separate
     * scheduled and frequency trip lists. Finally, check the runningScheduledTrips for overtaking.
     */
    public FilteredPattern(TripPattern source, BitSet servicesActive) {
        this.originalId = source.originalId;
        this.routeId = source.routeId;
        this.stops = source.stops;
        this.pickups = source.pickups;
        this.dropoffs = source.dropoffs;
        for (TripSchedule schedule : source.tripSchedules) {
            if (servicesActive.get(schedule.serviceCode)) {
                if (schedule.headwaySeconds == null) {
                    runningScheduledTrips.add(schedule);
                } else {
                    runningFrequencyTrips.add(schedule);
                }
            }
        }
        // These could be set more strictly when looping over tripSchedules; just use the source pattern's values for
        // now.
        this.hasFrequencies = source.hasFrequencies;
        this.hasSchedules = source.hasSchedules;

        // Check for overtaking
        boolean noScheduledOvertaking = true;
        loopOverStops:
        for (int stopOffset = 0; stopOffset < source.stops.length; stopOffset++) {
            for (int i = 0; i < runningScheduledTrips.size() - 1; i++) {
                if (runningScheduledTrips.get(i).departures[stopOffset] >
                        runningScheduledTrips.get(i + 1).departures[stopOffset]
                ) {
                    LOG.warn("Overtaking: route {} pattern {} stop #{} time {}",
                            routeId, originalId, stopOffset, runningScheduledTrips.get(i + 1).departures[stopOffset]);
                    noScheduledOvertaking = false;
                    break loopOverStops;
                }
            }
        }
        this.noScheduledOvertaking = noScheduledOvertaking;
    }
}