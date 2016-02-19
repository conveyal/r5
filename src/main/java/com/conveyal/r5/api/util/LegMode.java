package com.conveyal.r5.api.util;

/**
 * Modes of transport on ingress egress legs
 */
public enum  LegMode {
    WALK, BICYCLE,
    CAR,
    //Renting a bicycle
    BICYCLE_RENT,
    //Park & Ride
    CAR_PARK
}
