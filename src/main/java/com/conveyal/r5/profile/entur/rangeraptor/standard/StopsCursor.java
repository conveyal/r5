package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Access;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Transfer;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalViewAdapter.Transit;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;



/**
 * TODO TGR
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class StopsCursor<T extends TripScheduleInfo> {
    private Stops<T> stops;
    private final TransitCalculator transitCalculator;

    StopsCursor(Stops<T> stops, TransitCalculator transitCalculator) {
        this.stops = stops;
        this.transitCalculator = transitCalculator;
    }

    /**
     *
     * @param stop
     * @param transitDepartureTime
     * @return
     */
    StopArrivalView<T> access(int stop, int transitDepartureTime) {
        return newAccessView(stop, transitDepartureTime);
    }


    /**
     * Set cursor to the transit state at round and stop. Throws
     * runtime exception if round is 0 or no state exist.
     *
     * @param round the round to use.
     * @param stop the stop index to use.
     * @return the current transit state, if found
     */
    Transit<T> transit(int round, int stop) {
        if (round == 0) {
            throw new IllegalArgumentException("Transit legs are never the first leg...");
        }
        StopArrivalState<T> state = stops.get(round, stop);
        return new Transit<>(round, stop, state, this);
    }

    StopArrivalView<T> stop(int round, int stop) {
        return round == 0 ? newAccessView(stop) : newTransitOrTransferView(round, stop);
    }

    /**
     * Access with no known path - used to debug this state
     */
    private StopArrivalView<T> newAccessView(int stop) {
        StopArrivalState<T> arrival = stops.get(0, stop);
        return new Access<>(stop, arrival.accessDepartureTime(), arrival.time());
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
     */
    private StopArrivalView<T> newAccessView(int stop, int transitDepartureTime) {
        StopArrivalState<T> state = stops.get(0, stop);
        int departureTime = transitCalculator.originDepartureTime(transitDepartureTime, state.transferDuration());
        int arrivalTime = transitCalculator.accessLegArrivalTime(transitDepartureTime);
        return new Access<>(stop, departureTime, arrivalTime);
    }

    private StopArrivalView<T> newTransitOrTransferView(int round, int stop) {
        StopArrivalState<T> state = stops.get(round, stop);

        return state.arrivedByTransfer()
                ? new Transfer<>(round, stop, state, this)
                : new Transit<>(round, stop, state, this);
    }

}
