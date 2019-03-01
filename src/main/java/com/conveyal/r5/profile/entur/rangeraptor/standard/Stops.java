package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;

import java.util.function.Consumer;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class Stops<T extends TripScheduleInfo> {

    private final StopArrivalState<T>[][] stops;
    private final RoundProvider roundProvider;

    Stops(
            int nRounds,
            int nStops,
            Iterable<TransferLeg> egressLegs,
            RoundProvider roundProvider,
            Consumer<EgressStopArrivalState<T>> egressArrivalCallback
    ) {
        //noinspection unchecked
        this.stops = (StopArrivalState<T>[][]) new StopArrivalState[nRounds][nStops];
        this.roundProvider = roundProvider;
        createAndInsertEgressStopStates(nRounds, egressLegs, egressArrivalCallback);
    }

    void setInitialTime(int stop, int time, int duration) {
        findOrCreateStopIndex(round(), stop).setAccessTime(time, duration);
    }

    void transitToStop(int stop, int time, int boardStop, int boardTime, T trip, boolean bestTime) {
        StopArrivalState<T> state = findOrCreateStopIndex(round(), stop);

        state.arriveByTransit(time, boardStop, boardTime, trip);

        if (bestTime) {
            state.setBestTimeTransit(time);
        }
    }

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    void transferToStop(int fromStop, TransferLeg transferLeg, int arrivalTime) {
        int stop = transferLeg.stop();
        StopArrivalState<T> state = findOrCreateStopIndex(round(), stop);

        state.transferToStop(fromStop, arrivalTime, transferLeg.durationInSeconds());
    }

    int bestTimePreviousRound(int stop) {
        return get(round() - 1, stop).time();
    }

    boolean exist(int round, int stop) {
        StopArrivalState<T> s = get(round, stop);
        return s != null && s.reached();
    }

    StopArrivalState<T> get(int round, int stop) {
        return stops[round][stop];
    }


    /* private methods */

    private void createAndInsertEgressStopStates(int nRounds, Iterable<TransferLeg> egressLegs, Consumer<EgressStopArrivalState<T>> egressArrivalCallback) {
        for (int round = 1; round < nRounds; round++) {
            for (TransferLeg leg : egressLegs) {
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

    private int round() {
        return roundProvider.round();
    }
}
