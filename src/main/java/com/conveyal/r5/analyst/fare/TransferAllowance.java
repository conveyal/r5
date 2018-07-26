package com.conveyal.r5.analyst.fare;

/*
Standard GTFS Fare has a number of transfers allowed, but doesn't permit the value of those transfers to be
limited.  This class allows subsequent transfers to be limited both by number and value.  There has been discussion
in the GTFS Fares Working Group about a pay_difference_duration column for fare_attributes.  For now, this assumes
values specified in transfer_duration should also be considered pay_difference_duration.
 */

import com.conveyal.gtfs.model.Fare;

public class TransferAllowance {
    public final String fareId;
    public final int value;
    public final int number;
    public final int expirationTime;

    public TransferAllowance(){
        this.fareId = null;
        this.value = 0;
        this.number = 0;
        this.expirationTime = 0;
    }

    public TransferAllowance(Fare fare, int value, int startTime) {
        this.fareId = fare.fare_id;
        this.value = value;
        this.number = fare.fare_attribute.transfers;
        this.expirationTime = startTime + fare.fare_attribute.transfer_duration;
    }

    public TransferAllowance(String fareId, int value, int number, int expirationTime){
        this.fareId = fareId;
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

    public TransferAllowance clean(){
        int valueLimit = Math.max(0, this.value);
        int numberLimit = Math.max(0, this.number);
        return new TransferAllowance(this.fareId, valueLimit, numberLimit, this.expirationTime);

    }

    /**
     * Is this transfer allowance as good as or better than another transfer allowance? This does not consider the fare
     * paid so fare, and can be thought of as follows. If you are standing at a stop, and a perfectly trustworthy person
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
    public boolean isAsGoodAsOrBetterThanForAllPossibleFutureTrips (TransferAllowance other){
        return value >= other.value && expirationTime >= other.expirationTime && number >= other.number;
    }

}
