package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.EgressLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import java.util.function.Consumer;

final class EgressStopArrivalState<T extends TripScheduleInfo> extends StopArrivalState<T> {
    private final int round;
    private final EgressLeg egressLeg;
    private final Consumer<EgressStopArrivalState<T>> transitCallback;

    EgressStopArrivalState(int round, EgressLeg egressLeg, Consumer<EgressStopArrivalState<T>> transitCallback) {
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


    final EgressLeg egressLeg() {
        return egressLeg;
    }

    @Override
    void arriveByTransit(int time, int boardStop, int boardTime, T trip) {
        super.arriveByTransit(time, boardStop, boardTime, trip);
        transitCallback.accept(this);
    }

    int destinationDepartureTime() {
        return transitTime();
    }

    int destinationArrivalTime() {
        return destinationDepartureTime() + egressLeg.durationInSeconds();
    }

}
