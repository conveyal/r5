package com.conveyal.r5.analyst.fare;


import com.conveyal.gtfs.model.Fare;

/**
For Pareto searches that include as an optimization criterion monetary cost based on fares, we need to label states
with information about the potential value of future transfer allowances.  A standard GTFS fare_attribute can include
 a numeric limit on number of subsequent transfers, as well as a time_duration, but it doesn't have a good way of
 representing the potential value of a transfer allowance that a passenger may obtain upon paying a fare.  This class
 is meant to represent that value and restrictions on how it can be redeemed in subsequent rides. These restrictions
 are specific to fare systems and may include limitations on the number of subsequent transfers, which routes accept
 the transfer (e.g. only routes from a certain agency), and the duration of the value's validity.
 //TODO explain why fields are final.
 */
public class TransferAllowance {

    /**
    In the paper, value is referred to as "maximum transfer allowance" to emphasize that not all the value of the
     transfer allowance may be realized.
     */
    public final int value;
    public final int number;
    public final int expirationTime;

    /**
     * Constructor used for no transfer allowance
      */
    public TransferAllowance(){
        this.value = 0;
        this.number = 0;
        this.expirationTime = 0;
    }

    /**
     * @param fare GTFS fare used to describe where this allowance was obtained and set a limit on the number of
     *             subsequent transfers and duration of validity
     * @param value Value (e.g. USD converted to cents)
     * @param expirationTime Clock time at which the value expires
     */
    public TransferAllowance(Fare fare, int value, int expirationTime) {
        this.value = value;
        this.number = fare.fare_attribute.transfers;
        this.expirationTime = expirationTime;
    }

    /**
     * @param value Value (e.g. USD converted to cents)
     * @param number Number of transfers for which this value can be redeemed
     * @param expirationTime Clock time at which the value expires
     */
    public TransferAllowance(int value, int number, int expirationTime){
        this.value = value;
        this.number = number;
        this.expirationTime = expirationTime;
    }

    public boolean hasExpiredAt(int otherTime){
        return otherTime > expirationTime;
    }

    public int payDifference(int grossFare){
        return Math.max(0, grossFare - value);
    }

    public TransferAllowance tightenExpiration(int maxClockTime){
        // cap expiration time of transfer at max clock time of search, so that transfer slips that technically have more time
        // remaining, but that time cannot be used within the constraints of this search, can be pruned.

        // THIS METHOD SHOULD NOT BE USED BECAUSE IT INADVERTNELTY CONVERTS SUBCLASSES INTO REGULAR TRANSFERALLOWANCES
        // CAUSING PATHS THAT SHOULD NOT BE DISCARDED TO BE DISCARDED!
        throw new UnsupportedOperationException("tightenExpiration called unsafely. Override in subclasses.");

        //return new TransferAllowance(this.value, this.number, Math.min(this.expirationTime, maxClockTime));

    }

    /**
     * Is this transfer allowance as good as or better than another transfer allowance? This does not consider the fare
     * paid so far, and can be thought of as follows. If you are standing at a stop, and a perfectly trustworthy person
     * comes up to you and offers you two tickets, one with this transfer allowance, and one with the other transfer
     * allowance, is this one as good as or better than the other one for any trip that you might make? (Assume you have
     * no moral scruples about obtaining a transfer slip from someone else who is probably not supposed to be giving
     * them away).
     *
     * In the base class, this is true iff this transfer allowance has the same or higher value, and the same or later
     * expiration time, the same or higher number of transfers remaining. In subclasses for transit systems that have
     * different services, this may need to be overridden because not all transfer allowances are comparable. For example,
     * in Greater Boston, transfers from local bus can be applied to local bus, subway, or express bus; transfers from
     * subway can be applied to other subway services at the same station, local bus, or express bus, and transfers from
     * express bus can be applied to only local bus or subway. So the values of those three types of transfers are not
     * comparable.
     */
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other){
        return value >= other.value && expirationTime >= other.expirationTime && number >= other.number;
    }

}
