package com.conveyal.r5.api.util;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.vividsolutions.jts.util.Assert;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by mabu on 2.11.2015.
 */
public  class SegmentPattern implements Comparable<SegmentPattern> {
    /**
     * Trip Pattern id TODO: trippattern?
     * @notnull
     */
    public String patternId;

    /**
     * Index of stop in given TripPatern where trip was started
     * @notnull
     */
    public int fromIndex;

    /**
     * Index of stop in given TripPattern where trip was stopped
     * @notnull
     */
    public int toIndex;

    /**
     * Number of trips (on this pattern??)
     * @notnull
     */
    public int nTrips;

    //Do we have realTime data for arrival/deparure times
    public boolean realTime;

    //arrival times of from stop in this pattern
    public List<ZonedDateTime> fromArrivalTime;
    //departure times of from stop in this pattern
    public List<ZonedDateTime> fromDepartureTime;

    //arrival times of to stop in this pattern
    public List<ZonedDateTime> toArrivalTime;
    //departure times of to stop in this pattern
    public List<ZonedDateTime> toDepartureTime;

    public SegmentPattern(TransitLayer transitLayer, TripPattern pattern, int patternIdx, int boardStopIdx,
        int alightStopIdx, int alightTime, ZonedDateTime fromTimeDateZD) {
        fromArrivalTime = new ArrayList<>();
        fromDepartureTime = new ArrayList<>();
        toArrivalTime = new ArrayList<>();
        toDepartureTime = new ArrayList<>();
        realTime = false;
        patternId = Integer.toString(patternIdx);
        fromIndex = -1;
        toIndex = -1;

        //Finds at which indexes are board and alight stops in wanted trippattern
        //This is used in response and we need indexes to find used trip
        //it stops searching as soon as it founds both
        for(int i=0; i < pattern.stops.length; i++) {
            int currentStopIdx = pattern.stops[i];
            //From stop index wasn't found yet
            if (fromIndex == -1) {
                //Found from Stop
                if (boardStopIdx == currentStopIdx) {
                    fromIndex = i;
                    //If to stop was also found we can stop the search
                    if (toIndex != -1) {
                        break;
                    } else {
                        //Assumes that fromIndex and toIndex are not the same stops
                        continue;
                    }
                }
            }
            //To stop index wasn't found yet
            if (toIndex == -1) {
                //Found to stop index
                if (alightStopIdx == currentStopIdx) {
                    toIndex = i;
                    if (fromIndex != -1) {
                        break;
                    }
                }
            }
        }
        Assert.isTrue(fromIndex != -1);
        Assert.isTrue(toIndex != -1);
        //We search for a trip based on provided tripPattern board, alight stop and alightTime
        //TODO: this will be removed when support for tripIDs is added into Path
        for (TripSchedule schedule: pattern.tripSchedules) {
            if (schedule.arrivals[toIndex] == alightTime) {
                toArrivalTime.add(createTime(alightTime, fromTimeDateZD));
                toDepartureTime.add(createTime(schedule.departures[toIndex], fromTimeDateZD));

                fromArrivalTime.add(createTime(schedule.arrivals[fromIndex], fromTimeDateZD));
                fromDepartureTime.add(createTime(schedule.departures[fromIndex], fromTimeDateZD));
                break;
            }
        }


    }

    /**
     * Creates LocalDateTime based on seconds from midnight for time and date from requested ZonedDateTime
     *
     * @param time in seconds from midnight
     * @param fromTimeDateZD
     * @return
     */
    private ZonedDateTime createTime(int time, ZonedDateTime fromTimeDateZD) {
        //TODO: check timezones correct time etc. this is untested
        LocalDateTime localDateTime = LocalDateTime.of(fromTimeDateZD.getYear(), fromTimeDateZD.getMonth(), fromTimeDateZD.getDayOfMonth(), 0,0);
        return localDateTime.plusSeconds(time).atZone(fromTimeDateZD.getZone());
    }

    //TODO: from/to Departure/Arrival delay
    //TODO: should this be just the time or ISO date or local date
    //Probably ISO datetime

    @Override
    public int compareTo (SegmentPattern other) {
        return other.nTrips - this.nTrips;
    }
}
