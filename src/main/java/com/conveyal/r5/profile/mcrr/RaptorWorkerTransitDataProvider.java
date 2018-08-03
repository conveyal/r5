package com.conveyal.r5.profile.mcrr;

import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;

import java.util.BitSet;


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
public interface RaptorWorkerTransitDataProvider {

    /**
     * This method is called once, right after the constructor, before the routing start.
     * <p>
     * Strictly not needed, logic can be moved to constructor, but is separated out
     * to be able to messure performance as part of the rout method.
     */
    void init();

    /**
     * @return a map of distances from the given input stop to all other stops.
     */
    TIntList getTransfersDistancesInMMForStop(int stop);

    // TODO TGR - add JavaDoc
    TIntList getPatternsForStop(int stop);


    /**
     * The adapter need to know based on the request input (date) if a service is available or not.
     *
     * @param serviceCode The service code (index).
     * @return true if the service apply.
     */
    boolean skipCalendarService(int serviceCode);

    // TODO TGR - add JavaDoc
    int[] getScheduledIndexForOriginalPatternIndex();

    // TODO TGR - add JavaDoc
    PatternIterator patternIterator(BitSet patternsTouched);


    // TODO TGR - add JavaDoc
    interface PatternIterator {
        // TODO TGR - add JavaDoc
        boolean morePatterns();

        // TODO TGR - add JavaDoc
        Pattern next();
    }

    // TODO TGR - add JavaDoc
    interface Pattern {
        // TODO TGR - add JavaDoc
        int originalPatternIndex();

        // TODO TGR - add JavaDoc
        int currentPatternStop(int stopPositionInPattern);

        // TODO TGR - add JavaDoc
        int currentPatternStopsSize();

        // TODO TGR - add JavaDoc
        Iterable<TripSchedule> getTripSchedules();

        // TODO TGR - add JavaDoc
        int getTripSchedulesIndex(TripSchedule schedule);

        // TODO TGR - add JavaDoc
        TripSchedule getTripSchedule(int index);
    }
}
