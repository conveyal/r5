package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.function.Consumer;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class EgressStopArrivalState<T extends TripScheduleInfo> extends StopArrivalState<T> {
    private final int round;
    private final TransferLeg egressLeg;
    private final Consumer<EgressStopArrivalState<T>> transitCallback;

    EgressStopArrivalState(int round, TransferLeg egressLeg, Consumer<EgressStopArrivalState<T>> transitCallback) {
        this.round = round;
        this.egressLeg = egressLeg;
        this.transitCallback = transitCallback;
    }

    int round() {
        return round;
    }

    int stop() {
        return egressLeg.stop();
    }


    final TransferLeg egressLeg() {
        return egressLeg;
    }

    @Override
    void arriveByTransit(int time, int boardStop, int boardTime, T trip) {
        super.arriveByTransit(time, boardStop, boardTime, trip);
        transitCallback.accept(this);
    }
}
