package com.conveyal.r5.profile.entur.rangeraptor.standard;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.PathLeg;
import com.conveyal.r5.profile.entur.api.StopArrival;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.StopArrivalState;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static com.conveyal.r5.profile.entur.rangeraptor.StopArrivalState.NOT_SET;


/**
 * Class used to represent transit paths in Browsochrones and Modeify.
 */
class PathBuilderCursorBased<T extends TripScheduleInfo> {
    private final StopStateCursor<T> cursor;
    private int boardSlackInSeconds;
    private int round;


    PathBuilderCursorBased(StopStateCursor<T> cursor) {
        this.cursor = cursor;
    }

    void setBoardSlackInSeconds(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    /**
     * Scan over a raptor state and extract the path leading up to that state.
     */
    Path2<T> extractPathForStop(int maxRound, StopArrival egressStop, Collection<StopArrival> accessStops) {
        this.round = maxRound;
        int fromStopIndex = egressStop.stop();

        // find the fewest-transfers trip that is still optimal in terms of travel time
        StopArrivalState<T> state = findLastRoundWithTransitTimeSet(fromStopIndex);

        if (state == null) {
            return null;
        }
        List<PathLeg<T>> path = new ArrayList<>();
        PathLeg<T> egressLeg = new EgressLeg<>(fromStopIndex, state.transitTime(), egressStop.durationInSeconds());

        DebugState.debugStopHeader("EXTRACT PATH");
        //state.debugStop("egress stop", state.round(), stop);

        int toStopIndex;

        while (round > 0) {
            toStopIndex = fromStopIndex;
            fromStopIndex = state.boardStop();

            path.add(new TransitLeg<>(
                    fromStopIndex,
                    toStopIndex,
                    state
            ));

            state = cursor.stop(--round, fromStopIndex);

            if(state.arrivedByTransfer()) {
                toStopIndex = fromStopIndex;
                fromStopIndex = state.transferFromStop();

                path.add(new TransferLeg<>(
                        fromStopIndex,
                        toStopIndex,
                        state.time(),
                        state.transferTime()
                ));
                state = cursor.stop(round, fromStopIndex);
            }
        }

        final int accessStopIndex = fromStopIndex;
        StopArrival accessStop = accessStops.stream().filter(it -> it.stop() == accessStopIndex).findFirst().orElseThrow(() ->
                new IllegalStateException("Unable to find access stop in access times. Access stop= " + accessStopIndex
                        + ", access stops= " + accessStops)
        );

        // TODO TGR - This should be removed when access/egress becomes part of state.
        PathLeg<T> accessLeg = new AccessLeg<>(path.get(path.size()-1).fromTime() - boardSlackInSeconds , accessStop);

        return new Path<>(accessLeg, path, egressLeg);
    }

    /**
     * This method search the stop from roundMax and back to round 1 to find
     * the last round with a transit time set. This is sufficient for finding the
     * best time, since the state is only recorded iff it is faster then previous rounds.
     */
    private StopArrivalState<T> findLastRoundWithTransitTimeSet(int egressStop) {

        while (cursor.stopNotVisited(round, egressStop) || !cursor.stop(round, egressStop).arrivedByTransit()) {

            //debugListedStops("skip no transit", round, stop);
            --round;
            if (round == -1) {
                return null;
            }
        }
        return cursor.stop(round, egressStop);
    }

    static abstract class AbstractLeg<T extends TripScheduleInfo> implements PathLeg<T> {
        private int fromStop;
        private int toStop;
        private int fromTime;
        private int toTime;

        AbstractLeg(int fromStop, int toStop, int fromTime, int toTime) {
            this.fromStop = fromStop;
            this.toStop = toStop;
            this.fromTime = fromTime;
            this.toTime = toTime;
        }

        @Override public int fromStop()       { return fromStop;  }
        @Override public int fromTime()       { return fromTime; }
        @Override public int toStop()         { return toStop; }
        @Override public int toTime()         { return toTime; }
        @Override public T trip()           { return null; }
        @Override public boolean isTransit()  { return false; }
        @Override public boolean isTransfer() { return false; }
    }

    static final class TransitLeg<T extends TripScheduleInfo> extends AbstractLeg<T> {
        private T trip;

        TransitLeg(int boardStop, int alightStop, StopArrivalState<T> state) {
            super(boardStop, alightStop, state.boardTime(), state.transitTime());
            this.trip = state.trip();
        }

        @Override public T trip()          { return trip; }
        @Override public boolean isTransit() { return true; }
    }

    static final class TransferLeg<T extends TripScheduleInfo> extends AbstractLeg<T> {
        TransferLeg(int fromStop, int toStop, int toTime, int transferTime) {
            super(fromStop, toStop, toTime - transferTime, toTime);
        }
        @Override public boolean isTransfer() { return true; }
    }

    static final class AccessLeg<T extends TripScheduleInfo> extends AbstractLeg<T> {
        AccessLeg(int toTime, StopArrival stopArrival) {
            super(NOT_SET, stopArrival.stop(), toTime - stopArrival.durationInSeconds(), toTime);
        }
    }

    static final class EgressLeg<T extends TripScheduleInfo> extends AbstractLeg<T> {
        EgressLeg(int fromStop, int fromTime, int durationToStop) {
            super(fromStop, NOT_SET, fromTime, fromTime + durationToStop);
        }
    }

    static class Path<T extends TripScheduleInfo> implements Path2<T>, Iterable<PathLeg<T>> {
        private PathLeg<T> accessLeg;
        private List<PathLeg<T>> path;
        private PathLeg<T> egressLeg;

        public Path(PathLeg<T> accessLeg, List<PathLeg<T>> path, PathLeg<T> egressLeg) {
            this.accessLeg = accessLeg;
            this.path = path;
            this.egressLeg = egressLeg;

            Collections.reverse(path);
            validate();
        }

        void validate() {
            if (path.stream().noneMatch(PathLeg::isTransit)) {
                throw new IllegalStateException("Transit path computed without a transit segment!");
            }
        }

        @Override public PathLeg<T> accessLeg() {
            return accessLeg;
        }
        @Override public Iterable<PathLeg<T>> legs() {
            return path;
        }
        @Override public PathLeg<T> egressLeg() {
            return egressLeg;
        }
        @Override public Iterator<PathLeg<T>> iterator() {
            return path.iterator();
        }
    }
}
