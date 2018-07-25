package com.conveyal.r5.analyst.fare;

/*
Standard GTFS Fare does has a number of transfers allowed, but doesn't allow the value of those transfers to be limited.
This class allows subsequent transfers to be limited both by number and value.
 */

import com.conveyal.gtfs.model.Fare;

public class TransferPrivilege {
    public final String fareId;
    public final int valueLimit;
    public final int numberLimit;
    public final int expirationTime;

    public TransferPrivilege(){
        this.fareId = null;
        this.valueLimit = 0;
        this.numberLimit = 0;
        this.expirationTime = 0;
    }

    public TransferPrivilege(Fare fare, int startTime){
        this.fareId = fare.fare_id;
        this.valueLimit = Integer.MAX_VALUE;
        this.numberLimit = fare.fare_attribute.transfers;
        this.expirationTime = startTime + fare.fare_attribute.transfer_duration;
    }

    public TransferPrivilege(Fare fare, int valueLimit, int startTime){
        this.fareId = fare.fare_id;
        this.valueLimit = valueLimit;
        this.numberLimit = fare.fare_attribute.transfers;
        this.expirationTime = startTime + fare.fare_attribute.transfer_duration;
    }


    public TransferPrivilege(String fareId, int valueLimit, int numberLimit, int expirationTime){
        this.fareId = fareId;
        this.valueLimit = valueLimit;
        this.numberLimit = numberLimit;
        this.expirationTime = expirationTime;
    }

    public boolean hasExpiredAt(int otherTime){
        return otherTime > expirationTime;
    }

    public int redeemTransferValue(int grossFare){
        return Math.max(0, grossFare - valueLimit);
    }

    public TransferPrivilege clean(){
        int valueLimit = Math.max(0, this.valueLimit);
        int numberLimit = Math.max(0, this.numberLimit);
        return new TransferPrivilege(this.fareId, valueLimit, numberLimit, this.expirationTime);

    }

    public boolean canTransferPrivilegeDominate(TransferPrivilege other){
        return valueLimit == other.valueLimit && expirationTime >= other.expirationTime;
    }

}
