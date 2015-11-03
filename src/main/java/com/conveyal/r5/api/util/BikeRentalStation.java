package com.conveyal.r5.api.util;

import java.util.Set;

/**
 * Information about Bike rental station
 */
public class BikeRentalStation {

    //@notnull
    public String id;

    public String name;

    //Coordinates @notnull
    public float lat, lon;

    public int bikesAvailable;

    public int spacesAvailable;

    public boolean allowDropoff = true;

    public Set<String> networks;

    public boolean realTimeData = false;

}