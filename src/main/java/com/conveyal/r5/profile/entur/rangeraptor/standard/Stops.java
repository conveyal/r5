package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.transit.EgressLeg;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Consumer;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class Stops<T extends TripScheduleInfo> {

    private final StopArrivalState<T>[][] stops;

    Stops(int nRounds, int nStops, Iterable<EgressLeg> egressLegs, Consumer<EgressStopArrivalState<T>> egressArrivalCallback) {
        //noinspection unchecked
        this.stops = (StopArrivalState<T>[][]) new StopArrivalState[nRounds][nStops];
        createAndInsertEgressStopStates(nRounds, egressLegs, egressArrivalCallback);
    }

    void setInitialTime(int round, int stop, int time) {
        findOrCreateStopIndex(round, stop).setAccessTime(time);
    }

    void transitToStop(int round, int stop, int time, int boardStop, int boardTime, T trip, boolean bestTime) {
        StopArrivalState<T> state = findOrCreateStopIndex(round, stop);

        state.arriveByTransit(time, boardStop, boardTime, trip);

        if (bestTime) {
            state.setBestTimeTransit(time);
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    void transferToStop(int round, int fromStop, TransferLeg transferLeg, int arrivalTime) {
        int stop = transferLeg.stop();
        StopArrivalState<T> state = findOrCreateStopIndex(round, stop);

        state.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
    }

    StopArrivalState<T> get(int round, int stop) {
        return stops[round][stop];
    }


    /* private methods */

    private void createAndInsertEgressStopStates(int nRounds, Iterable<EgressLeg> egressLegs, Consumer<EgressStopArrivalState<T>> egressArrivalCallback) {
        for (int round = 1; round < nRounds; round++) {
            for (EgressLeg leg : egressLegs) {
                EgressStopArrivalState<T> state = new EgressStopArrivalState<>(round, leg, egressArrivalCallback);
                stops[round][leg.stop()] = state;
            }
        }
    }

    private StopArrivalState<T> findOrCreateStopIndex(final int round, final int stop) {
        if (stops[round][stop] == null) {
            stops[round][stop] = new StopArrivalState<>();
        }
        return get(round, stop);
    }
}
