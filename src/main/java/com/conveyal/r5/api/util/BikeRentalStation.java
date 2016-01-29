package com.conveyal.r5.api.util;

import java.io.Serializable;
import java.util.Set;

/**
 * Information about Bike rental station
 */
public class BikeRentalStation implements Serializable {

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

    @Override
    public String toString() {
        String sb = "BikeRentalStation{" + "id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", lat=" + lat +
            ", lon=" + lon +
            ", bikesAvailable=" + bikesAvailable +
            ", spacesAvailable=" + spacesAvailable +
            ", allowDropoff=" + allowDropoff +
            ", networks=" + networks +
            ", realTimeData=" + realTimeData +
            '}';
        return sb;
    }
}