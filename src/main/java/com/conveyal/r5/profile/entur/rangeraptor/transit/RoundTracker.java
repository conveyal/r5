package com.conveyal.r5.profile.entur.rangeraptor.transit;


import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;

/**
 * Round tracker to keep track of round index and when to stop exploring new rounds.
 */
public class RoundTracker implements RoundProvider {

    /**
     * The extra number of rounds/transfers we accept compared to the trip with
     * the fewest number of transfers. This is used to abort the search.
     */
    private final int numberOfAdditionalTransfers;

    /**
     * The current round in progress (round index).
     */
    private int round = 0;


    /**
     * The round upper limit for when to abort the search.
     * <p/>
     * This is default set to the maximum number of rounds limit, but as soon as
     * the destination is reach the {@link #numberOfAdditionalTransfers} is used to
     * update the limit.
     * <p/>
     * The limit is inclusive, indicating the the last round to process.
     */
    private int roundMaxLimit;


    RoundTracker(int nRounds, int numberOfAdditionalTransfers, WorkerLifeCycle lifeCycle) {
        // The 'roundMaxLimit' is inclusive, while the 'nRounds' is exclusive; Hence subtract 1.
        this.roundMaxLimit = nRounds - 1;
        this.numberOfAdditionalTransfers = numberOfAdditionalTransfers;
        lifeCycle.onSetupIteration(this::setupIteration);
        lifeCycle.onPrepareForNextRound(this::prepareForNextRound);
        lifeCycle.onRoundComplete(this::roundComplete);
    }

    /**
     * Before each iteration, initialize the round to 0.
     */
    private void setupIteration(int ignoreIterationDepartureTime) {
        round = 0;
    }

    private void roundComplete(boolean destinationReached) {
        if(destinationReached) {
            recalculateMaxLimitBasedOnDestinationReachedinCurrentRound();
        }
    }

    /**
     * Is there more rounds to process (or is the upper limit reached).
     */
    public boolean hasMoreRounds() {
        return round < roundMaxLimit;
    }

    /**
     * Prepare for next round by incrementing round index.
     */
    private void prepareForNextRound() {
        ++round;
    }

    /**
     * Return the current round, the round in process.
     */
    public int round() {
        return round;
    }


    /* private methods */

    private void recalculateMaxLimitBasedOnDestinationReachedinCurrentRound() {
        roundMaxLimit = Math.min(roundMaxLimit, round + numberOfAdditionalTransfers);
    }
}
