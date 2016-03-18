package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adjust headways on a route.
 */
public class AdjustFrequency extends Modification {
    public static final long serialVersionUID = 1L;

    /** The new headway, in seconds */
    public int headway;

    private static final Logger LOG = LoggerFactory.getLogger(AdjustFrequency.class);

    @Override
    public String getType() {
        return "adjust-frequency";
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // if (matches(tt.tripId)) {
        // Do nothing, stub. TODO Implement
        return false;
    }

}
