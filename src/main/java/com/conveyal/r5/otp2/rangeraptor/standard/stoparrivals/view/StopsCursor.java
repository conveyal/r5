package com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view;

import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.StopArrivalState;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.Stops;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view.StopArrivalViewAdapter.Access;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view.StopArrivalViewAdapter.Transfer;
import com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals.view.StopArrivalViewAdapter.Transit;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;



/**
 * Used to create a view to the internal StdRangeRaptor model and to navigate
 * between stop arrivals. Since view objects are only used for path and debugging
 * operations, the view can create temporary objects for each StopArrival. These
 * view objects are temporary objects and when the algorithm progress they might
 * get invalid - so do not keep references to these objects bejond the scope of
 * of a the callers method.
 * <p/>
 * The design was originally done to support the FLyweight design pattern.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class StopsCursor<T extends TripScheduleInfo> {
    private Stops<T> stops;
    private final TransitCalculator transitCalculator;

    public StopsCursor(Stops<T> stops, TransitCalculator transitCalculator) {
        this.stops = stops;
        this.transitCalculator = transitCalculator;
    }

    public boolean exist(int round, int stop) {
        return stops.exist(round, stop);
    }


    /**
     * Return a fictive Transit arrival for the rejected transit stop arrival.
     */
    public Transit<T> rejectedTransit(int round, int alightStop, int alightTime, T trip, int boardStop, int boardTime) {
            StopArrivalState<T> arrival = new StopArrivalState<>();
            arrival.arriveByTransit(alightTime, boardStop, boardTime, trip);
            return new StopArrivalViewAdapter.Transit<>(round, alightStop, arrival, this);
    }

    /**
     * Return a fictive Transfer arrival for the rejected transfer stop arrival.
     */
    public Transfer<T> rejectedTransfer(int round, int fromStop, TransferLeg transferLeg, int toStop, int arrivalTime) {
            StopArrivalState<T> arrival = new StopArrivalState<>();
            arrival.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
            return new StopArrivalViewAdapter.Transfer<>(round, toStop, arrival, this);
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
     */
    ArrivalView<T> access(int stop, int transitDepartureTime) {
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
    public Transit<T> transit(int round, int stop) {
        StopArrivalState<T> state = stops.get(round, stop);
        return new Transit<>(round, stop, state, this);
    }

    public ArrivalView<T> stop(int round, int stop) {
        return round == 0 ? newAccessView(stop) : newTransitOrTransferView(round, stop);
    }

    /**
     * Access without known transit, uses the iteration departure time without time shift
     */
    private ArrivalView<T> newAccessView(int stop) {
        StopArrivalState<T> arrival = stops.get(0, stop);
        int departureTime = transitCalculator.minusDuration(arrival.time(), arrival.accessDuration());
        return new Access<>(stop, departureTime, arrival.time());
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
     */
    private ArrivalView<T> newAccessView(int stop, int transitDepartureTime) {
        StopArrivalState<T> state = stops.get(0, stop);
        int departureTime = transitCalculator.originDepartureTime(transitDepartureTime, state.accessDuration());
        int arrivalTime = transitCalculator.plusDuration(departureTime, state.accessDuration());
        return new Access<>(stop, departureTime, arrivalTime);
    }

    private ArrivalView<T> newTransitOrTransferView(int round, int stop) {
        StopArrivalState<T> state = stops.get(round, stop);

        return state.arrivedByTransfer()
                ? new Transfer<>(round, stop, state, this)
                : new Transit<>(round, stop, state, this);
    }

    int departureTime(int arrivalTime, int legDuration) {
        return transitCalculator.minusDuration(arrivalTime, legDuration);
    }
}
