package com.conveyal.r5.api.util;

import java.time.ZonedDateTime;

/**
 * Created by mabu on 21.12.2015.
 */
public class Itinerary {
    //Waiting time between transfers in seconds
    public int waitingTime;

    //Time when walking in seconds
    public int walkTime;

    //Distance in meters of all non-transit parts of this itinerary
    public int distance;

    //TODO: walking, cycling, driving distance/time?

    //Number of transfers between different transit vehicles
    public int transfers;

    //How much time did whole trip took in seconds
    public int duration;

    //How much time did we spend on transit in seconds
    public int transitTime;

    //ISO 8061 date time when this journey started
    public ZonedDateTime startTime;

    //ISO 8061 date time when this journey was over
    public ZonedDateTime endTime;
}
