package com.conveyal.r5.analyst.cluster;

/**
 * This is a stopgap to allow reducers to return either travel times, or accessibility figures, or both.
 */
public class TravelTimeComputerResult {

    public byte[] travelTimesFromOrigin;
    public RegionalWorkResult accessibilityValuesAtOrigin;

}
