package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Collection;

/**
 * Adjust the dwell times on matched trips.
 */
public class AdjustDwellTime extends Modification {
    public static final long serialVersionUID = 1L;

    /** Stops for which to set the dwell time */
    public Collection<String> stopId;

    /** new dwell time in seconds */
    public int dwellTime;

    @Override
    public String getType() {
        return "adjust-dwell-time";
    }

    @Override public boolean apply (TransportNetwork network) {
       // Do nothing, stub. TODO Implement
       return false;
    }

}
