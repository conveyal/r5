package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;

import static com.conveyal.r5.profile.entur.rangeraptor.StopState.NOT_SET;

abstract class McPathLeg<S extends McStopState<T>, T extends TripScheduleInfo> implements PathLeg<T> {
    final S state;

    private McPathLeg(S state) {
        this.state = state;
    }

    static <T extends TripScheduleInfo> PathLeg<T> createAccessLeg(McAccessStopState<T> state, int boardTimeFirstTransitLeg) {
        return new AccessLeg<>(state, boardTimeFirstTransitLeg);
    }

    static <T extends TripScheduleInfo> PathLeg<T> createLeg(McStopState<T> state) {
        if(state instanceof McTransferStopState) {
            return new TransferLeg<>((McTransferStopState<T>) state);
        }
        if(state instanceof McTransitStopState) {
            return new TransitLeg<>((McTransitStopState<T>) state);
        }
        throw new IllegalStateException("Unsupported type: " + state.getClass());
    }

    static <T extends TripScheduleInfo> PathLeg createEgressLeg(McTransitStopState<T> state, int egressTime) {
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


    private static final class TransitLeg<T extends TripScheduleInfo> extends McPathLeg<McTransitStopState<T>, T> {

        private TransitLeg(McTransitStopState<T> state) {
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

    private static final class TransferLeg<T extends TripScheduleInfo> extends McPathLeg<McTransferStopState<T>, T> {

        private TransferLeg(McTransferStopState<T> state) {
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

    private static final class AccessLeg<T extends TripScheduleInfo> extends McPathLeg<McAccessStopState<T>, T> {
        private final int fromTime;
        private final int toTime;

        private AccessLeg(McAccessStopState<T> state, int boardTimeFirstTransitLeg) {
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
    private static final class EgressLeg<T extends TripScheduleInfo> extends McPathLeg<McTransitStopState<T>, T> {
        private int egressTime;

        private EgressLeg(McTransitStopState<T> fromState, int egressTime) {
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
