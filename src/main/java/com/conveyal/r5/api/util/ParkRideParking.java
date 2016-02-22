package com.conveyal.r5.api.util;

import java.io.Serializable;

/**
 * Information about P+R parking lots
 */
public class ParkRideParking implements Serializable {
    //@notnull
    public String id;

    public String name;

    //Number of all spaces
    public int capacity;
}
