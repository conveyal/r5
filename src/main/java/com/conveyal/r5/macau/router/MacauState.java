package com.conveyal.r5.macau.router;

import com.conveyal.r5.macau.distribution.Distribution;
import com.conveyal.r5.macau.distribution.OrDistribution;

import java.util.BitSet;

/**
 * State for one round of the probabilistic router.
 * We use the same structure for transfer results and ride results.
 */
public class MacauState {

    // The state at each stop is an "or" distribution of independent events.
    final OrDistribution[] distributionsAtStops;

    final BitSet updatedStops;

    public MacauState (int size) {
        // Leave them all null to save space until needed
        this.distributionsAtStops = new OrDistribution[size];
        this.updatedStops = new BitSet(size);
    }

    /** Copy constructor. Protectively deep-copies the distributions at stops, but not recursively. */
    public MacauState (MacauState source) {
        this(source.distributionsAtStops.length);
        for (int s = 0; s < distributionsAtStops.length; s++) {
            OrDistribution sourceDistribution = source.distributionsAtStops[s];
            if (sourceDistribution != null) {
                distributionsAtStops[s] = sourceDistribution.copy();
            }
        }
        // TODO updatedStops bitset
    }

    /**
     * @return whether or not the stop was updated
     */
    public boolean add (int stop, Distribution candidate) {
        OrDistribution distributionAtStop = distributionsAtStops[stop];
        if (distributionAtStop == null) {
            distributionAtStop = new OrDistribution();
            // TODO insert the distribution from the previous round and check for update
            distributionsAtStops[stop] = distributionAtStop;
        }
        boolean updated = distributionAtStop.addAndPrune(candidate);
        if (updated) {
            updatedStops.set(stop);
        }
        return updated;
    }


    public MacauState copy() {
        return new MacauState(this);
    }

}
