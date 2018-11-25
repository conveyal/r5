package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Access;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Transfer;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Transit;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TimeInterval;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;



/**
 * TODO TGR
 */
class StopsCursor<T extends TripScheduleInfo> {
    private Stops<T> stops;
    private final TransitCalculator transitCalculator;

    private StopArrivalView<T> current = null;

    StopsCursor(Stops<T> stops, TransitCalculator transitCalculator) {
        this.stops = stops;
        this.transitCalculator = transitCalculator;
    }

    public StopArrivalView<T> current() {
        return current;
    }

    public StopArrivalView<T> previous() {
        int round, stop;

        if(current.arrivedByTransit()) {
            round = current.round() - 1;
            stop = current.boardStop();

            if(round == 0) {
                current = newAccessView(stop, current.departureTime());
            }
            else {
                current = newTransitOrTransferView(round, stop);
            }
        }
        else if(current.arrivedByTransfer()) {
            round = current.round();
            stop = current.transferFromStop();

            current = new Transit<>(round, stop, stops.get(round, stop), this::previous);
        }
        return current;
    }

    /**
     * Set cursor to the transit state at round and stop. Throws
     * runtime exception if round is 0 or no state exist.
     *
     * @param round the round to use.
     * @param stop the stop index to use.
     * @return the current transit state, if found
     */
    StopArrivalView<T> transit(int round, int stop) {
        if (round == 0) {
            throw new IllegalArgumentException("Transit legs are never the first leg...");
        }
        else {
            StopArrivalState<T> state = stops.get(round, stop);
            current = new Transit<>(round, stop, state, this::previous);
        }
        return current;
    }

    StopArrivalView<T> stop(int round, int stop) {
        if (round == 0) {
            current = newAccessView(stop);
        }
        else {
            current = newTransitOrTransferView(round, stop);
        }
        return current;
    }

    /**
     * Access with no known path - used to debug this state
     */
    private StopArrivalView<T> newAccessView(int stop) {
        assert stop >= 0;
        int arrivalTime = stops.get(0, stop).time();
        int departureTime = transitCalculator.originDepartureTimeAtStop(stop, arrivalTime);
        return new Access<>(stop, departureTime, arrivalTime);
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
     */
    private StopArrivalView<T> newAccessView(int stop, int transitDepartureTime) {
        TimeInterval interval = transitCalculator.accessLegTimeIntervalAtStop(stop, transitDepartureTime);
        return new Access<>(stop, interval);
    }

    private StopArrivalView<T> newTransitOrTransferView(int round, int stop) {
        StopArrivalState<T> state = stops.get(round, stop);

        return state.arrivedByTransfer()
                ? new Transfer<>(round, stop, state, this::previous)
                : new Transit<>(round, stop, state, this::previous);
    }
}
