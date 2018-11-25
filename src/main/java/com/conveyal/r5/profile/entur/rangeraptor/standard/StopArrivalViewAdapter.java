package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TimeInterval;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;

import java.util.function.Supplier;


/**
 * TODO TGR
 */
abstract class StopArrivalViewAdapter<T extends TripScheduleInfo> implements StopArrivalView<T> {
    private final int round;
    private final int stop;

    private StopArrivalViewAdapter(int round, int stop) {
        this.round = round;
        this.stop = stop;
    }

    @Override
    public int stop() {
        return stop;
    }

    @Override
    public int round() {
        return round;
    }


    static final class Access<T extends TripScheduleInfo> extends StopArrivalViewAdapter<T> {
        private final int departureTime;
        private final int arrivalTime;

        Access(int stop, int departureTime, int arrivalTime) {
            super(0, stop);
            this.departureTime = departureTime;
            this.arrivalTime = arrivalTime;
        }

        Access(int stop, TimeInterval interval) {
            this(stop, interval.from, interval.to);
        }

        @Override
        public int arrivalTime() {
            return arrivalTime;
        }

        @Override
        public int departureTime() {
            return departureTime;
        }

        @Override
        public boolean arrivedByAccessLeg() {
            return true;
        }
    }

    static final class Transit<T extends TripScheduleInfo> extends StopArrivalViewAdapter<T> {
        private final StopArrivalState<T> arrival;
        private final Supplier<StopArrivalView<T>> previousCallback;

        Transit(int round, int stop, StopArrivalState<T> arrival, Supplier<StopArrivalView<T>> previousCallback) {
            super(round, stop);
            this.arrival = arrival;
            this.previousCallback = previousCallback;
        }

        @Override
        public int arrivalTime() {
            return arrival.transitTime();
        }

        @Override
        public int departureTime() {
            return arrival.boardTime();
        }

        @Override
        public int boardStop() {
            return arrival.boardStop();
        }

        @Override
        public T trip() {
            return arrival.trip();
        }

        @Override
        public boolean arrivedByTransit() {
            return true;
        }

        @Override
        public StopArrivalView<T> previous() {
            return previousCallback.get();
        }
    }

    static final class Transfer<T extends TripScheduleInfo> extends StopArrivalViewAdapter<T> {
        private final StopArrivalState<T> arrival;
        private final Supplier<StopArrivalView<T>> previousCallback;

        Transfer(int round, int stop, StopArrivalState<T> arrival, Supplier<StopArrivalView<T>> previousCallback) {
            super(round, stop);
            this.arrival = arrival;
            this.previousCallback = previousCallback;
        }

        @Override
        public int transferFromStop() {
            return arrival.transferFromStop();
        }

        @Override
        public int arrivalTime() {
            return arrival.time();
        }

        @Override
        public int departureTime() {
            return arrivalTime() - arrival.transferDuration();
        }

        @Override
        public boolean arrivedByTransfer() {
            return true;
        }

        @Override
        public StopArrivalView<T> previous() {
            return previousCallback.get();
        }
    }

    static final class DestinationArrivalViewAdapter<T extends TripScheduleInfo> implements DestinationArrivalView<T> {
        private final int departureTime;
        private final int arrivalTime;
        private final Supplier<StopArrivalView<T>> previousCallback;

        DestinationArrivalViewAdapter(int departureTime, int arrivalTime, Supplier<StopArrivalView<T>> previousCallback) {
            this.arrivalTime = arrivalTime;
            this.departureTime = departureTime;
            this.previousCallback = previousCallback;
        }

        @Override
        public int departureTime() {
            return departureTime;
        }

        @Override
        public int arrivalTime() {
            return arrivalTime;
        }

        @Override
        public StopArrivalView<T> previous() {
            return previousCallback.get();
        }
    }
}