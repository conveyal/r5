package com.conveyal.r5.profile.entur.api;


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
    default void init() {}

    /**
     * This method is responsible for providing all transfers from a given stop to all
     * possible stops around that stop.
     * <p/>
     * The implementation may implement a lightweight {@link StopArrival} representation.
     * The iterator element only needs to be valid for the duration og a single iterator step.
     * Hence; It is safe to use a cursor/flyweight pattern to represent both the StopArrival
     * and the Iterator<StopArrival> - this will most likely be the best performing
     * implementation.
     * <p/>
     * Example:
     * <pre>
     *class LightweightTransferIterator implements Iterator&lt;StopArrival&gt;, StopArrival {
     *     private static final int[] EMPTY_ARRAY = new int[0];
     *     private final int[] a;
     *     private int index;
     *
     *     LightweightTransferIterator(int[] a) {
     *         this.a = a == null ? EMPTY_ARRAY : a;
     *         this.index = this.a.length == 0 ? 0 : -2;
     *     }
     *
     *     public int stop()              { return a[index]; }
     *     public int durationInSeconds() { return a[index+1]; }
     *     public boolean hasNext()       { index += 2; return index < a.length; }
     *     public StopArrival next()   { return this; }
     * }
     * </pre>
     * @return a map of distances from the given input stop to all other stops.
     */
    Iterator<StopArrival> getTransfers(int fromStop);

    /**
     * Return a set of all patterns visiting the given set of stops.
     * <p/>
     * The implementation may implement a lightweight {@link Pattern} representation.
     * See {@link #getTransfers(int)} for detail on how to implement this.
     *
     * @param stops set of stops for find all patterns for.
     */
    Iterator<Pattern> patternIterator(UnsignedIntIterator stops);

    /**
     * The provider needs to know based on the request input (date) if a service is available or not.
     *
     * @param trip The trip to check.
     * @return true if the trip schedule is in service.
     */
    boolean isTripScheduleInService(TripScheduleInfo trip);


    /**
     * This is the total number of stops, it should be possible to retrieve transfers and pattern for every stop
     * from 0 to {@code numberOfStops()-1}.
     */
    int numberOfStops();
}
