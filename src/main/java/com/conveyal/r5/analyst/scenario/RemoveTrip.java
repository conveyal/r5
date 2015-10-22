package com.conveyal.r5.analyst.scenario;

import org.onebusaway.gtfs.model.Trip;
import com.conveyal.r5.routing.edgetype.TripPattern;
import com.conveyal.r5.routing.trippattern.FrequencyEntry;
import com.conveyal.r5.routing.trippattern.TripTimes;

/**
 * Remove trips from a scenario
 */
public class RemoveTrip extends TripFilter {
    public static final long serialVersionUID = 1L;

    @Override
    public String getType() {
        return "remove-trip";
    }

    @Override
    public TripTimes apply(Trip trip, TripPattern tp, TripTimes tt) {
        return matches(trip) ? null : tt;
    }

    @Override
    public FrequencyEntry apply(Trip trip, TripPattern tp, FrequencyEntry fe) {
        return matches(trip) ? null : fe;
    }
}
