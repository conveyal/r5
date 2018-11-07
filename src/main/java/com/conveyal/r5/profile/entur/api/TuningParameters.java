package com.conveyal.r5.profile.entur.api;


import java.util.function.Supplier;


/**
 * Tuning parameters - changing these parameters change the performance (speed and/or memory consumption).
 */
public interface TuningParameters {

    /**
     * This parameter is used to allocate enough memory space for the search.
     * Set it to the maximum number of transfers for any given itinerary expected to
     * be found within the entire transit network.
     * <p/>
     * Default value is 12.
     */
    default int maxNumberOfTransfers() {
        return 12;
    }
}
