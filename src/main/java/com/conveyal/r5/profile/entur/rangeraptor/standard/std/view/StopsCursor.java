package com.conveyal.r5.profile.entur.rangeraptor.standard.std.view;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.EgressStopArrivalState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.StopArrivalState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.Stops;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopArrivalViewAdapter.Access;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopArrivalViewAdapter.Transfer;
import com.conveyal.r5.profile.entur.rangeraptor.standard.std.view.StopArrivalViewAdapter.Transit;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;



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

    public DestinationArrivalView<T> destinationArrival(EgressStopArrivalState<T> arrival) {
        return new StopArrivalViewAdapter.DestinationArrivalViewAdapter<T>(
                arrival.transitTime(),
                transitCalculator.add(arrival.transitTime(), arrival.egressLeg().durationInSeconds()),
                arrival.round() - 1,
                transit(arrival.round(), arrival.stop())
        );
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
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
        StopArrivalState<T> state = stops.get(round, stop);
        return new Transit<>(round, stop, state, this);
    }

    public StopArrivalView<T> stop(int round, int stop) {
        return round == 0 ? newAccessView(stop) : newTransitOrTransferView(round, stop);
    }

    /**
     * Access without known transit, uses the iteration departure time without time shift
     */
    private StopArrivalView<T> newAccessView(int stop) {
        StopArrivalState<T> arrival = stops.get(0, stop);
        int departureTime = transitCalculator.sub(arrival.time(), arrival.accessDuration());
        return new Access<>(stop, departureTime, arrival.time());
    }

    /**
     * A access stop arrival, time-shifted according to the first transit boarding/departure time
     */
    private StopArrivalView<T> newAccessView(int stop, int transitDepartureTime) {
        StopArrivalState<T> state = stops.get(0, stop);
        int departureTime = transitCalculator.originDepartureTime(transitDepartureTime, state.accessDuration());
        int arrivalTime = transitCalculator.add(departureTime, state.accessDuration());
        return new Access<>(stop, departureTime, arrivalTime);
    }

    private StopArrivalView<T> newTransitOrTransferView(int round, int stop) {
        StopArrivalState<T> state = stops.get(round, stop);

        return state.arrivedByTransfer()
                ? new Transfer<>(round, stop, state, this)
                : new Transit<>(round, stop, state, this);
    }

    int departureTime(int arrivalTime, int legDuration) {
        return transitCalculator.sub(arrivalTime, legDuration);
    }
}
