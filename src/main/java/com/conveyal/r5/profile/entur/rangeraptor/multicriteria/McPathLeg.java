package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import static com.conveyal.r5.profile.entur.rangeraptor.StopArrivalState.NOT_SET;

abstract class McPathLeg<S extends McStopArrivalState<T>, T extends TripScheduleInfo> implements PathLeg<T> {
    final S state;

    private McPathLeg(S state) {
        this.state = state;
    }

    static <T extends TripScheduleInfo> PathLeg<T> createAccessLeg(McAccessStopArrivalState<T> state, int boardTimeFirstTransitLeg) {
        return new AccessLeg<>(state, boardTimeFirstTransitLeg);
    }

    static <T extends TripScheduleInfo> PathLeg<T> createLeg(McStopArrivalState<T> state) {
        if(state instanceof McTransferStopArrivalState) {
            return new TransferLeg<>((McTransferStopArrivalState<T>) state);
        }
        if(state instanceof McTransitStopArrivalState) {
            return new TransitLeg<>((McTransitStopArrivalState<T>) state);
        }
        throw new IllegalStateException("Unsupported type: " + state.getClass());
    }

    static <T extends TripScheduleInfo> PathLeg createEgressLeg(McTransitStopArrivalState<T> state, int egressTime) {
        return new EgressLeg<>(state, egressTime);
    }

    @Override
    public int toStop() {
        return state.stopIndex();
    }

    @Override
    public int toTime() {
        return state.time();
    }

    @Override
    public T trip() {
        return state.trip();
    }

    @Override
    public boolean isTransfer() {
        return state.arrivedByTransfer();
    }

    @Override
    public boolean isTransit() {
        return state.arrivedByTransit();
    }


    private static final class TransitLeg<T extends TripScheduleInfo> extends McPathLeg<McTransitStopArrivalState<T>, T> {

        private TransitLeg(McTransitStopArrivalState<T> state) {
            super(state);
        }

        @Override
        public int fromStop() {
            return state.boardStop();
        }

        @Override
        public int fromTime() {
            return state.boardTime();
        }
    }

    private static final class TransferLeg<T extends TripScheduleInfo> extends McPathLeg<McTransferStopArrivalState<T>, T> {

        private TransferLeg(McTransferStopArrivalState<T> state) {
            super(state);
        }

        @Override
        public int fromStop() {
            return state.previousState().stopIndex();
        }

        @Override
        public int fromTime() {
            return state.time() - state.transferTime();
        }
    }

    private static final class AccessLeg<T extends TripScheduleInfo> extends McPathLeg<McAccessStopArrivalState<T>, T> {
        private final int fromTime;
        private final int toTime;

        private AccessLeg(McAccessStopArrivalState<T> state, int boardTimeFirstTransitLeg) {
            super(state);
            this.toTime = boardTimeFirstTransitLeg - state.boardSlackInSeconds;
            this.fromTime = this.toTime - state.accessDurationInSeconds;
        }

        @Override public int fromStop() {
            return NOT_SET;
        }

        @Override public int fromTime() { return fromTime; }

        @Override public int toTime() {
            return toTime;
        }
    }


    /** TODO TGR  - This class should be simplefied, when the egress path becomes part of mc raptor */
    private static final class EgressLeg<T extends TripScheduleInfo> extends McPathLeg<McTransitStopArrivalState<T>, T> {
        private int egressTime;

        private EgressLeg(McTransitStopArrivalState<T> fromState, int egressTime) {
            super(fromState);
            this.egressTime = egressTime;
        }

        @Override
        public int fromStop() {
            return state.stopIndex();
        }

        @Override
        public int fromTime() {
            return state.time();
        }

        @Override
        public int toTime() {
            return state.time() + egressTime;
        }

        @Override
        public boolean isTransfer() {
            return false;
        }

        @Override
        public boolean isTransit() {
            return false;
        }
    }
}
