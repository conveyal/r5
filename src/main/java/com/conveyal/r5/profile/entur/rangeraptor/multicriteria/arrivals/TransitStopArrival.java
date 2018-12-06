package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class TransitStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final T trip;
    private boolean arrivedByTransitLastRound = true;

    public TransitStopArrival(AbstractStopArrival<T> previousState, int round, int stopIndex, int arrivalTime, int boardTime, T trip) {
        super(
                previousState,
                round,
                stopIndex,
                boardTime,
                arrivalTime,
                previousState.cost()
        );
        this.trip = trip;
    }

    @Override
    public boolean arrivedByTransit() {
        return true;
    }

    @Override
    public T trip() {
        return trip;
    }

    @Override
    public int boardStop() {
        return previousStop();
    }

    /**
     * This method return true if we arrived at this stop in the last round.
     * <p/>
     * NOTE! This method does not know about witch round it is, it just assume
     * that is will be called ONCE pr round and that it can return 'true' the
     * first time it is called.
     */
    public boolean arrivedByTransitLastRound() {
        if (arrivedByTransitLastRound) {
            arrivedByTransitLastRound = false;
            return true;
        }
        return false;
    }
}
