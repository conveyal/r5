package com.conveyal.r5.api.util;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transit stop
 */
public class Stop {
    //GTFS Stop ID @notnull
    @JsonProperty("id")
    public String stopId;
    //Stop name @notnull
    public String name;
    //Coordinate @notnull
    public float lat, lon;
    //Short text or number that identifies this stop to passengers
    public String code;
    //Fare zone for stop
    public String zoneId;
    public int wheelchairBoarding = 0;

    public Stop(String stopId, String name) {
        this.stopId = stopId;
        this.name = name;
    }
}
