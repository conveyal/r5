package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Collection;
import java.util.Set;

/**
 * Adjust the dwell times on matched trips.
 *
 * TODO maybe this should be merged into AdjustSpeed with new fields: dwellTimeSeconds, dwellTimeScale boolean or float, turn off scaling hops with factor 1
 */
public class AdjustDwellTime extends Modification {

    public static final long serialVersionUID = 1L;

    /** Stops for which to set the dwell time */
    public Set<String> stops;

    /** New dwell time in seconds. */
    public int dwellSecs = -1;

    @Override
    public String getType() {
        return "adjust-dwell-time";
    }

    @Override public boolean apply (TransportNetwork network) {
       // Do nothing, stub. TODO Implement
       return false;
    }

}
