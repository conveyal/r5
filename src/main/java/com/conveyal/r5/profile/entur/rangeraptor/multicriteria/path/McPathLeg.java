package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.path;

import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.DestinationArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;


abstract class McPathLeg<S extends AbstractStopArrival<T>, T extends TripScheduleInfo> implements PathLeg<T> {
    final S state;

    private McPathLeg(S state) {
        this.state = state;
    }

    static <T extends TripScheduleInfo> PathLeg<T> createAccessLeg(AccessStopArrival<T> state, int boardTimeFirstTransitLeg) {
        return new AccessLeg<>(state, boardTimeFirstTransitLeg);
    }

    static <T extends TripScheduleInfo> PathLeg<T> createLeg(AbstractStopArrival<T> state) {
        if(state instanceof TransferStopArrival) {
            return new TransferLeg<>((TransferStopArrival<T>) state);
        }
        if(state instanceof TransitStopArrival) {
            return new TransitLeg<>((TransitStopArrival<T>) state);
        }
        throw new IllegalStateException("Unsupported type: " + state.getClass());
    }

    static <T extends TripScheduleInfo> PathLeg<T> createEgressLeg(DestinationArrival<T> destinationArrival) {
        return new EgressLeg<>(destinationArrival);
    }

    @Override public int toStop() {
        return state.stopIndex();
    }
    @Override public int toTime() {
        return state.time();
    }

    private static final class TransitLeg<T extends TripScheduleInfo> extends McPathLeg<TransitStopArrival<T>, T> {
        private TransitLeg(TransitStopArrival<T> state) { super(state); }
        @Override public int fromStop() { return state.boardStop(); }
        @Override public int fromTime() { return state.boardTime(); }
        @Override public T trip() { return state.trip(); }
        @Override public boolean isTransit() { return true; }
    }

    private static final class TransferLeg<T extends TripScheduleInfo> extends McPathLeg<TransferStopArrival<T>, T> {
        private TransferLeg(TransferStopArrival<T> state) { super(state); }
        @Override public int fromStop() { return state.previousArrival().stopIndex(); }
        @Override public int fromTime() { return state.time() - state.transferTime(); }
        @Override public boolean isTransfer() { return true; }
    }

    private static final class AccessLeg<T extends TripScheduleInfo> implements PathLeg<T> {
        private final int fromTime;
        private final int toTime;
        private final int toStop;

        private AccessLeg(AccessStopArrival<T> accessArrival, int firstTransitBoardTime) {
            this.fromTime = accessArrival.originFromTime(firstTransitBoardTime);
            this.toStop = accessArrival.stopIndex();
            this.toTime = fromTime + accessArrival.accessDurationInSeconds;
        }

        @Override public int fromTime() { return fromTime; }
        @Override public int toTime() { return toTime; }
        @Override public int toStop() { return toStop; }
    }

    private static final class EgressLeg<T extends TripScheduleInfo> implements PathLeg<T> {
        private DestinationArrival<T> destinationArrival;

        private EgressLeg(DestinationArrival<T> destinationArrival) {
            this.destinationArrival = destinationArrival;
        }
        @Override public int fromStop() { return destinationArrival.getPreviousState().stopIndex(); }
        @Override public int fromTime() { return destinationArrival.getPreviousState().time(); }
        @Override public int toTime() { return destinationArrival.getArrivalTime(); }
    }
}
