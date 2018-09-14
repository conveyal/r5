package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.PathLeg;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.NOT_SET;

abstract class McPathLeg<S extends McStopState> implements PathLeg {
    final S state;

    private McPathLeg(S state) {
        this.state = state;
    }

    static PathLeg createAccessLeg(McAccessStopState state, int boardTimeFirstTransitLeg) {
        return new AccessLeg(state, boardTimeFirstTransitLeg);
    }

    static PathLeg createLeg(McStopState state) {
        return state.arrivedByTransit()
                ? new TransitLeg((McTransitStopState) state)
                : new TransferLeg((McTransferStopState) state);
    }

    static PathLeg createEgressLeg(McTransitStopState state, int egressTime) {
        return new EgressLeg(state, egressTime);
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
    public int pattern() {
        return state.pattern();
    }

    @Override
    public int trip() {
        return state.trip();
    }

    @Override
    public int transferTime() {
        return state.transitTime();
    }

    @Override
    public boolean isTransfer() {
        return state.arrivedByTransfer();
    }

    @Override
    public boolean isTransit() {
        return state.arrivedByTransit();
    }


    private static final class TransitLeg extends McPathLeg<McTransitStopState> {

        private TransitLeg(McTransitStopState state) {
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

    private static final class TransferLeg extends McPathLeg<McTransferStopState> {

        private TransferLeg(McTransferStopState state) {
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

    private static final class AccessLeg extends McPathLeg<McAccessStopState> {
        private final int fromTime;
        private final int toTime;

        private AccessLeg(McAccessStopState state, int boardTimeFirstTransitLeg) {
            super(state);
            this.toTime = boardTimeFirstTransitLeg - state.boardSlackInSeconds;
            this.fromTime = this.toTime - state.accessDuationInSeconds;
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
    private static final class EgressLeg extends McPathLeg<McTransitStopState> {
        private int egressTime;

        private EgressLeg(McTransitStopState fromState, int egressTime) {
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
