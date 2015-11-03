package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adjust headways on a route.
 */
public class AdjustHeadway extends TripScheduleModification {
    public static final long serialVersionUID = 1L;

    /** The new headway, in seconds */
    public int headway;

    private static final Logger LOG = LoggerFactory.getLogger(AdjustHeadway.class);

    @Override
    public TripSchedule applyToTripSchedule(TripPattern tp, TripSchedule tt) {
        if (matches(tt.tripId)) {
            LOG.warn("Not performing requested headway adjustment on timetabled trip {}", tripId);
        }
        return tt;
    }

    @Override
    public String getType() {
        return "adjust-headway";
    }
}
