package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.PathLeg;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.NOT_SET;

abstract class McPathLeg implements PathLeg {
    final McStopState state;

    private McPathLeg(McStopState state) {
        this.state = state;
    }

    static PathLeg createAccessLeg(McStopState state, int fromTime) {
        return new AccessLeg(state, fromTime);
    }

    static PathLeg createTransitLeg(McStopState state) {
        return new TransitLeg(state);
    }

    static PathLeg createTransferLeg(McStopState state) {
        return new TransferLeg(state);
    }

    static PathLeg createEgressLeg(McStopState state, int egressTime) {
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


    private static final class TransitLeg extends McPathLeg {

        private TransitLeg(McStopState state) {
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

    private static final class TransferLeg extends McPathLeg {

        private TransferLeg(McStopState state) {
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

    private static final class AccessLeg extends McPathLeg {
        private int fromTime;

        private AccessLeg(McStopState state, int fromTime) {
            super(state);
            this.fromTime = fromTime;
        }

        @Override
        public int fromStop() {
            return NOT_SET;
        }

        @Override
        public int fromTime() {
            return fromTime;
        }
    }


    /** TODO TGR  - This class should be simplefied, when the egress path becomes part of mc raptor */
    private static final class EgressLeg extends McPathLeg {
        private int egressTime;

        private EgressLeg(McStopState fromState, int egressTime) {
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
