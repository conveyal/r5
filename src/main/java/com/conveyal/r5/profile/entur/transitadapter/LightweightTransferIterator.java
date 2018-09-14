package com.conveyal.r5.profile.entur.transitadapter;

import com.conveyal.r5.profile.entur.api.DurationToStop;

import java.util.Iterator;


/**
 * Create a lightweigth DutationToStop iterator, using a singel object to represent
 * the iterator and all instances of the DurationToStop. The DurationToStop is
 * only valid for the duration of on step
 */
class LightweightTransferIterator implements Iterator<DurationToStop>, DurationToStop {
    private final int[] durationToStops;
    private int index;

    LightweightTransferIterator(int[] durationToStops) {
        this.durationToStops = durationToStops;
        reset();
    }


    /* Iterator<DurationToStop> methods */

    @Override
    public boolean hasNext() {
        index += 2;
        return index < durationToStops.length;
    }

    @Override
    public DurationToStop next() {
        return this;
    }

    /* DurationToStop, lightweight implementation */

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
     * enables the iterator to be reused, but be carefull to not use it in a multi
     * threaded environment.
     */
    void reset() {
        this.index = this.durationToStops.length == 0 ? 0 : -2;
    }
}
