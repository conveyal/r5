package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class TransitStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final T trip;

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
}
