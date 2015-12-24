package com.conveyal.r5.api.util;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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

    public List<PointToPointConnection> connection;

    //ISO 8061 date time when this journey started
    public ZonedDateTime startTime;

    //ISO 8061 date time when this journey was over
    public ZonedDateTime endTime;

    /**
     * Creates itinerary from streetSegment
     *
     * It assumes it is a direct path (without transit)
     * startTime is set fromTimeDataZD
     * endTime is set from startTime plus duration of streetSegment
     * @param streetSegment
     * @param accessIndex with which accessIndex is this itinerary made
     * @param fromTimeDateZD
     */
    public Itinerary(StreetSegment streetSegment, int accessIndex, ZonedDateTime fromTimeDateZD) {
        connection = new ArrayList<>();
        transfers = 0;
        waitingTime = 0;
        walkTime = duration = streetSegment.duration;
        transitTime = 0;
        startTime = fromTimeDateZD;
        endTime = fromTimeDateZD.plusSeconds(streetSegment.duration);
        PointToPointConnection pointToPointConnection = new PointToPointConnection();
        pointToPointConnection.access = accessIndex;
        connection.add(pointToPointConnection);
    }
}
