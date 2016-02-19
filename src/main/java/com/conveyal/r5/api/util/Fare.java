package com.conveyal.r5.api.util;

/**
 * Created by mabu on 30.10.2015.
 */
public class Fare {

    //FIXME: Change type to RideType
    public String type;
    public float low;
    public float peak;
    public float senior;
    public boolean transferReduction;

    public static Fare SampleFare() {
        Fare fare = new Fare();
        fare.type = "NORMAL";
        fare.low = 0.7f;
        fare.peak = 1.2f;
        fare.senior = 0.8f;
        fare.transferReduction = false;
        return fare;
    }

    public String getType() {
        return type;
    }

    public float getLow() {
        return low;
    }

    public float getPeak() {
        return peak;
    }

    public float getSenior() {
        return senior;
    }

    public boolean isTransferReduction() {
        return transferReduction;
    }
}
