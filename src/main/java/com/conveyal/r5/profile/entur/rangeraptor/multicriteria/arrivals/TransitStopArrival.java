package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class TransitStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    // TODO TGR - Implement a dynamic cost function
    private static final int BOARD_COST = 300;
    private final T trip;

    public TransitStopArrival(AbstractStopArrival<T> previousState, int stopIndex, int arrivalTime, int boardTime, T trip) {
        super(
                previousState,
                stopIndex,
                boardTime,
                arrivalTime,
                arrivalTime - previousState.arrivalTime() + BOARD_COST
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
}
