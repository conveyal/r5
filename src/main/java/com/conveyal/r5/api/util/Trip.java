package com.conveyal.r5.api.util;

/**
 * One specific GTFS trip
 */
public class Trip {
    /**
     * GTFS trip ID
     * @notnull
     */
    public String tripId;

    /**
     * Generated Service ID
     */
    public String serviceId;


    public Boolean wheelchairAccessible = false;

    public Boolean bikesAllowed = false;
}
