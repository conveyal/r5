package com.conveyal.r5.otp2.rangeraptor.standard.stoparrivals;


import com.conveyal.r5.otp2.api.transit.TransferLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.RoundProvider;
import com.conveyal.r5.otp2.rangeraptor.standard.BestNumberOfTransfers;

import java.util.function.Consumer;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class Stops<T extends TripScheduleInfo> implements BestNumberOfTransfers {

    private final StopArrivalState<T>[][] stops;
    private final RoundProvider roundProvider;

    public Stops(
            int nRounds,
            int nStops,
            RoundProvider roundProvider
    ) {
        this.roundProvider = roundProvider;
        //noinspection unchecked
        this.stops = (StopArrivalState<T>[][]) new StopArrivalState[nRounds][nStops];
    }

    /**
     * Setup egress arrivals with a callback witch is notified when a new transit egress arrival happens.
     */
    public void setupEgressStopStates(
            Iterable<TransferLeg> egressLegs,
            Consumer<EgressStopArrivalState<T>> transitArrivalCallback
    ) {
        for (int round = 1; round < stops.length; round++) {
            for (TransferLeg leg : egressLegs) {
                EgressStopArrivalState<T> state = new EgressStopArrivalState<>(
                        round,
                        leg,
                        transitArrivalCallback
                );
                stops[round][leg.stop()] = state;
            }
        }
    }

    public boolean exist(int round, int stop) {
        StopArrivalState<T> s = get(round, stop);
        return s != null && s.reached();
    }

    public StopArrivalState<T> get(int round, int stop) {
        return stops[round][stop];
    }

    @Override
    public int calculateMinNumberOfTransfers(int stop) {
        for (int i = 0; i < stops.length; i++) {
            if(stops[i][stop] != null) {
                return i;
            }
        }
        return unreachedMinNumberOfTransfers();
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


    /* private methods */

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
