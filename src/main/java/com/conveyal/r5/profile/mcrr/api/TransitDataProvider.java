package com.conveyal.r5.profile.mcrr.api;

import com.conveyal.r5.profile.mcrr.BitSetIterator;

import java.util.Iterator;


/**
 * <p>
 *     This interface defines the data needed for the RangeRaptorWorker
 *     to do transit. {@link com.conveyal.r5.transit.TransitLayer} contains
 *     all that data - but not exactly in the flavour needed by the
 *     Worker, so creating this interface define that role, and make it
 *     possible to write small adapter in between. This also simplify
 *     the use of the Worker with other data sources, importing
 *     and adapting this code into other software like OTP.
 * </p>
 * <p>
 *     The implementation of this is refered to as the *adapter*.
 * </p>
 */
public interface TransitDataProvider {

    /**
     * This method is called once, right after the constructor, before the routing start.
     * <p>
     * Strictly not needed, logic can be moved to constructor, but is separated out
     * to be able to measure performance as part of the route method.
     */
    void init();

    /**
     * @return a map of distances from the given input stop to all other stops.
     */
    Iterable<DurationToStop> getTransfers(int fromStop);

    /**
     * Return a set of all patterns visiting the given set of stops.
     * @param stops the set of stops
     */
    Iterator<Pattern> patternIterator(BitSetIterator stops);

    /**
     * The adapter need to know based on the request input (date) if a service is available or not.
     *
     * @param serviceCode The service code (index).
     * @return true if the service apply.
     */
    boolean skipCalendarService(int serviceCode);

}
