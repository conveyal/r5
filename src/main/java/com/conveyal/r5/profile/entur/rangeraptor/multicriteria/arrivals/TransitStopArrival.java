package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

public final class TransitStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final int boardTime;
    private final T trip;
    private boolean arrivedByTransitLastRound = true;

    public TransitStopArrival(AbstractStopArrival<T> previousState, int round, int stopIndex, int time, int boardTime, T trip) {
        super(previousState, round, round * 2, stopIndex, time, previousState.cost());
        this.trip = trip;
        this.boardTime = boardTime;
    }

    @Override
    public int transitTime() {
        return time();
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

    @Override
    public int boardTime() {
        return boardTime;
    }

    /**
     * The 'origin from time' is when the journey started. The access leg is time-shifted
     * towards the first transit leg.
     */
    @Override
    int originFromTime() {
        // An access leg is allways followed by an Transit leg; Hence the
        // implementation is put here and not in the super class
        if(previousArrival() instanceof AccessStopArrival) {
            return ((AccessStopArrival) previousArrival()).originFromTime(boardTime);
        }
        // If this transit is not the first, propagate forward to previous leg
        return previousArrival().originFromTime();
    }

    /**
     * This method return true if we arrived at this stop in the last round.
     * <p/>
     * NOTE! This method does not know about witch round it is, it just assume
     * that is will be called ONCE pr round and that it can return 'true' the
     * first time it is called.
     */
    public boolean arrivedByTransitLastRound() {
        if(arrivedByTransitLastRound) {
            arrivedByTransitLastRound = false;
            return true;
        }
        return false;
    }
}
