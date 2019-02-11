package com.conveyal.r5.profile.entur.transitadapter;

import com.conveyal.r5.profile.entur.api.transit.TransferLeg;

import java.util.Iterator;


/**
 * Create a lightweight TransferLeg iterator, using a single object to represent
 * the iterator and all instances of the TransferLeg. The TransferLeg is
 * only valid for the duration of one step.
 * <p/>
 * NOT THREAD SAFE!
 */
class LightweightTransferIterator implements Iterator<TransferLeg>, TransferLeg {
    private final int[] durationToStops;
    private int index;

    LightweightTransferIterator(int[] durationToStops) {
        this.durationToStops = durationToStops;
        reset();
    }


    /* Iterator<TransferLeg> methods */

    @Override
    public boolean hasNext() {
        index += 2;
        return index < durationToStops.length;
    }

    @Override
    public TransferLeg next() {
        return this;
    }

    /* TransferLeg, lightweight implementation */

    @Override
    public int stop() {
        return durationToStops[index];
    }

    @Override
    public int durationInSeconds() {
        return durationToStops[index + 1];
    }

    /**
     * Used to reset the iterator, to start at the beginning again. This
     * enables the iterator to be reused, but be careful to not use it in a multi
     * threaded environment.
     * <p/>
     * NOT THREAD SAFE!
     */
    void reset() {
        this.index = this.durationToStops.length == 0 ? 0 : -2;
    }
}
