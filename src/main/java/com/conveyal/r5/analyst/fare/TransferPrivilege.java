package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;

/*
Keeps track of the transfer value conferred by a fare, which may expire after a certain time.
 */

public class TransferPrivilege {
    private int maxValue;
    private int valueAvailable;
    private int expirationTime;
    public String fareId;

    public TransferPrivilege(){
        this.maxValue = 0;
    }

    public void setMaxValue (int maxValue){
        this.maxValue = maxValue;
    }

    public void set(int value, int time, String fareId){
        this.valueAvailable = Math.min(value, maxValue);
        this.expirationTime = time;
        this.fareId = fareId;
    }

    public void set(int value, String fareId){
        this.valueAvailable = value;
        this.fareId = fareId;
    }

    public int val(){
        return valueAvailable;
    }

    public void clear(){
        this.valueAvailable = 0;
        this.expirationTime = Integer.MAX_VALUE;
        this.fareId = "";
    }

    public void checkExpiration(int otherTime){
        if (otherTime > expirationTime) this.clear();
    }

    public int redeemTransfer(int grossFare){
        return Math.max(0, grossFare - valueAvailable);
    }

    public boolean isTransferPrivilegeComparableTo(TransferPrivilege other){
        return true;
    }

}
