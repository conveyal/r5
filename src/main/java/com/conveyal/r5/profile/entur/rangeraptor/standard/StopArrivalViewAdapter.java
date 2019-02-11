package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;


/**
 * TODO TGR -
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
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

        @Override
        public int arrivalTime() {
            return arrivalTime;
        }

        @Override
        public int departureTime() {
            return departureTime;
        }

        @Override
        public int departureTimeAccess(int transitBoardTime) {
            return departureTime;
        }

        @Override
        public int arrivalTimeAccess(int transitBoardTime) {
            return arrivalTime;
        }

        @Override
        public boolean arrivedByAccessLeg() {
            return true;
        }

        @Override
        public StopArrivalView<T> previous() {
            throw new UnsupportedOperationException("Access arrival is the first leg.");
        }
    }

    static final class Transit<T extends TripScheduleInfo> extends StopArrivalViewAdapter<T> {
        private final StopArrivalState<T> arrival;
        private final StopsCursor<T> cursor;

        Transit(int round, int stop, StopArrivalState<T> arrival, StopsCursor<T> cursor) {
            super(round, stop);
            this.arrival = arrival;
            this.cursor = cursor;
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
            return round() == 1
                    ? cursor.access(boardStop(), departureTime())
                    : cursor.stop(round()-1, boardStop());
        }
    }

    static final class Transfer<T extends TripScheduleInfo> extends StopArrivalViewAdapter<T> {
        private final StopArrivalState<T> arrival;
        private final StopsCursor<T> cursor;

        Transfer(int round, int stop, StopArrivalState<T> arrival, StopsCursor<T> cursor) {
            super(round, stop);
            this.arrival = arrival;
            this.cursor = cursor;
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
            return cursor.departureTime(arrivalTime(), arrival.transferDuration());
        }

        @Override
        public boolean arrivedByTransfer() {
            return true;
        }

        @Override
        public StopArrivalView<T> previous() {
            return cursor.transit(round(), transferFromStop());
        }
    }

    static final class DestinationArrivalViewAdapter<T extends TripScheduleInfo> implements DestinationArrivalView<T> {
        private final int departureTime;
        private final int arrivalTime;
        private final Transit<T> previous;

        DestinationArrivalViewAdapter(int departureTime, int arrivalTime, Transit<T> previous) {
            this.arrivalTime = arrivalTime;
            this.departureTime = departureTime;
            this.previous = previous;
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
        public Transit<T> previous() {
            return previous;
        }
    }
}