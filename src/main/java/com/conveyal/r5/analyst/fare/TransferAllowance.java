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

    public boolean canTransferPrivilegeDominate(TransferAllowance other){
        return value == other.value && expirationTime >= other.expirationTime;
    }

}
