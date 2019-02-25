package com.conveyal.r5.profile.entur.rangeraptor.standard;


/**
 * Round tracker to keep track of round index and when to stop exploring new rounds.
 * <p/>
 *
 */
class RoundTracker {

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

    RoundTracker(int nRounds, int numberOfAdditionalTransfers) {
        // The 'roundMaxLimit' is inclusive, while the 'nRounds' is exclusive; Hence subtract 1.
        this.roundMaxLimit = nRounds - 1;
        this.numberOfAdditionalTransfers = numberOfAdditionalTransfers;
    }

    /**
     * Before each iteration, initialize the round to 0.
     */
    void setupIteration() {
        round = 0;
    }

    /**
     * Is there more rounds to process (or is the upper limit reached).
     */
    boolean hasMoreRounds() {
        return round < roundMaxLimit;
    }

    /**
     * Prepare for next round by incrementing round index.
     */
    void prepareForNextRound() {
        ++round;
    }

    /**
     * Return the current round, the round in process.
     */
    public int round() {
        return round;
    }

    /**
     * The destination is reached in the current round.
     */
    void notifyDestinationReached() {
        roundMaxLimit = Math.min(roundMaxLimit, round + numberOfAdditionalTransfers);
    }
}
