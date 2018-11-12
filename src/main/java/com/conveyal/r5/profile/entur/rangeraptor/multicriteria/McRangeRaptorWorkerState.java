package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.AccessLeg;
import com.conveyal.r5.profile.entur.api.EgressLeg;
import com.conveyal.r5.profile.entur.api.TransferLeg;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.util.BitSetIterator;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerState;
import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.rangeraptor.DebugState;

import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;


/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p/>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * want the Algorithm to be as clean as possible and to be able to swap the state implementation - try out and
 * experiment with different state implementations.
 * <p/>
 *
 */
final class McRangeRaptorWorkerState<T extends TripScheduleInfo> implements WorkerState {

    /** Stop the search when the time exceeds the max time limit. */
    private int maxTimeLimit;

    private final Stops<T> stops;
    private final int nRounds;
    private int round = Integer.MIN_VALUE;

    private BitSet touchedCurrent;
    private BitSet touchedPrevious;


    /** create a RaptorState for a network with a particular number of stops, and a given maximum duration */
    McRangeRaptorWorkerState(int nRounds, int nStops, Collection<EgressLeg> egressLegs) {
        this.nRounds = nRounds;
        this.stops = new Stops<>(nStops, egressLegs);

        this.touchedCurrent = new BitSet(nStops);
        this.touchedPrevious = new BitSet(nStops);
    }

    @Override public void initNewDepartureForMinute(int departureTime) {
        // TODO TGR - Set max limit to 5 days for now, replace this with a pareto check against the
        // TODO TGR - destination location values.
        maxTimeLimit = departureTime + 5 * 24 * 60 * 60;
        // clear all touched stops to avoid constant rexploration
        touchedCurrent.clear();
        touchedPrevious.clear();
        round = 0;
    }

    @Override public void setInitialTime(AccessLeg accessLeg, int fromTime, int boardSlackInSeconds) {
        stops.setInitialTime(accessLeg, fromTime, boardSlackInSeconds);
        touchedCurrent.set(accessLeg.stop());
        debugStops(AccessStopArrival.class, round, accessLeg.stop());
    }

    @Override public boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds-1;
        return moreRoundsToGo && isCurrentRoundUpdated();
    }

    @Override public void gotoNextRound () {
        ++round;
    }

    BitSetIterator stopsTouchedPreviousRound() {
        mergeAndSwapTouchedStops();
        return new BitSetIterator(touchedPrevious);
    }

    @Override public BitSetIterator stopsTouchedByTransitCurrentRound() {
        swapTouchedStops();
        return new BitSetIterator(touchedPrevious);
    }

    Iterable<? extends AbstractStopArrival<T>> listStopStatesPreviousRound(int stop) {
        return stops.list(round-1, stop);
    }


    /**
     * Set the time at a transit stop iff it is optimal.
     */
    void transitToStop(AbstractStopArrival<T> boardStop, int stop, int alightTime, int boardTime, T trip) {
        if (alightTime > maxTimeLimit) {
            return;
        }

        boolean added = stops.transitToStop(boardStop, round, stop, alightTime, trip, boardTime);

        if (added) {
            touchedCurrent.set(stop);
            // skip: transferTimes
            debugStops(TransitStopArrival.class, round, stop);
        }
    }

    /**
     * Set the time at a transit stops iff it is optimal.
     */
    @Override public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {
        Iterable<? extends AbstractStopArrival<T>> fromArrivals = stops.listArrivedByTransitLastRound(fromStop);

        while (transfers.hasNext()) {
            TransferLeg toStop = transfers.next();
            transferToStop(fromArrivals, toStop);
        }
    }

    private void transferToStop(Iterable<? extends AbstractStopArrival<T>> fromArrivals, TransferLeg transfer) {

        final int targetStop = transfer.stop();
        final int transferTimeInSeconds = transfer.durationInSeconds();

        for(AbstractStopArrival<T> it :  fromArrivals) {
            int arrivalTime = it.time() + transferTimeInSeconds;

            if (arrivalTime < maxTimeLimit) {
                if(stops.transferToStop(it, round, transfer, arrivalTime)) {
                    touchedCurrent.set(targetStop);
                }
            }
        }
        debugStops(TransferStopArrival.class, round, targetStop);
    }

    Collection<Path2<T>> extractPaths() {
        return stops.extractPaths();
    }

    @Override public void debugStopHeader(String title) {
        DebugState.debugStopHeader(title,"C P");
    }


    /* private methods */

    private boolean isCurrentRoundUpdated() {
        return !touchedCurrent.isEmpty();
    }

    private void mergeAndSwapTouchedStops() {
        touchedCurrent.or(touchedPrevious);
        swapTouchedStops();
    }

    private void swapTouchedStops() {
        BitSet temp = touchedPrevious;
        touchedPrevious = touchedCurrent;
        touchedCurrent = temp;
        touchedCurrent.clear();
    }

    private void debugStops(Class<?> type, int round, int stop) {
        if (DebugState.isDebug(stop)) {
            String postfix = (touchedCurrent.get(stop) ? "x " : "  ") + (touchedPrevious.get(stop) ? "x" : " ");
            for (AbstractStopArrival<T> it : stops.list(round, stop)) {
                if(it.getClass() == type) {
                    DebugState.debugStop(round, stop, it, postfix);
                }
            }
        }
    }
}
