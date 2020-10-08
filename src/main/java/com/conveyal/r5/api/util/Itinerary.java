package com.conveyal.r5.api.util;

import java.time.ZonedDateTime;

/**
 * Object represents specific trip at a specific point in time with specific access, transit and egress parts
 */
public class Itinerary {
    //Waiting time between transfers in seconds
    public int waitingTime;

    //Time when walking in seconds
    public int walkTime;

    //Distance in mm of all non-transit parts of this itinerary @notnull
    public int distance;

    //TODO: walking, cycling, driving distance/time?

    //Number of transfers between different transit vehicles
    public int transfers;

    //How much time did whole trip took in seconds @notnull
    public int duration;

    //How much time did we spend on transit in seconds @notnull
    public int transitTime;

    public PointToPointConnection connection;

    //ISO 8061 date time when this journey started @notnull
    public ZonedDateTime startTime;

    //ISO 8061 date time when this journey was over @notnull
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
        transfers = 0;
        waitingTime = 0;
        walkTime = duration = streetSegment.duration;
        distance = streetSegment.distance;
        transitTime = 0;
        startTime = fromTimeDateZD;
        endTime = fromTimeDateZD.plusSeconds(streetSegment.duration);
        PointToPointConnection pointToPointConnection = new PointToPointConnection(accessIndex);
        connection = pointToPointConnection;
    }

    public Itinerary() {

    }

    public void addConnection(PointToPointConnection pointToPointConnection) {
        connection = pointToPointConnection;
    }

    /**
     * Adds durationSeconds to walkTime and updates waitingTime
     */
    public void addWalkTime(int durationSeconds) {
        walkTime+=durationSeconds;
        //Updates waiting time
        waitingTime=duration-(transitTime+walkTime);
    }
}
