package com.conveyal.r5.api.util;

import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by mabu on 2.11.2015.
 */
public  class SegmentPattern implements Comparable<SegmentPattern> {

    private static final Logger LOG = LoggerFactory.getLogger(SegmentPattern.class);

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

    //This is used to find if there already exist same alight time in SegmentPattern
    //So that each time is only inserted once
    private List<Integer> alightTimesCache;
    final public int patternIdx;
    final public int routeIndex;

    //List of tripIDs with trips whose times are used
    public List<String> tripIds;

    private TransitLayer transitLayer;

    public SegmentPattern(TransitLayer transitLayer, TripPattern pattern, Path currentPath,
        int pathIndex, ZonedDateTime fromTimeDateZD) {
        fromArrivalTime = new ArrayList<>();
        fromDepartureTime = new ArrayList<>();
        toArrivalTime = new ArrayList<>();
        toDepartureTime = new ArrayList<>();
        alightTimesCache = new ArrayList<>();
        tripIds = new ArrayList<>();
        realTime = false;
        int patternIdx = currentPath.patterns[pathIndex];
        int alightTime = currentPath.alightTimes[pathIndex];
        int tripIndex = currentPath.trips[pathIndex];
        int boardStopPosition = currentPath.boardStopPositions[pathIndex];
        int alightStopPosition = currentPath.alightStopPositions[pathIndex];
        patternId = Integer.toString(patternIdx);
        this.patternIdx = patternIdx;
        fromIndex = boardStopPosition;
        toIndex = alightStopPosition;
        routeIndex = pattern.routeIndex;
        this.transitLayer = transitLayer;

        addTime(pattern, alightTime, fromTimeDateZD, tripIndex);

    }

    /**
     * Fills to/from arrival/Departure time arrays with time information for trip.
     *
     * Trip schedule is read from pattern trip schedules and Raptor algorithm tripIndex
     *
     * Time is created based on seconds from midnight and date and timezone from fromTimeDateZD with help of {@link #createTime(int, ZonedDateTime)}.
     * @param pattern TripPattern for current trip
     * @param alightTime seconds from midnight time for trip we are searching for
     * @param fromTimeDateZD time/date object from which date and timezone is read
     * @param tripIndex inside pattern
     * @return index of created time
     */
    private int addTime(TripPattern pattern, int alightTime, ZonedDateTime fromTimeDateZD,
        int tripIndex) {
        //From/to arrival/departure times are added based on trip times
        TripSchedule schedule = pattern.tripSchedules.get(tripIndex);

        toArrivalTime.add(createTime(alightTime, fromTimeDateZD));
        toDepartureTime.add(createTime(schedule.departures[toIndex], fromTimeDateZD));

        fromArrivalTime.add(createTime(schedule.arrivals[fromIndex], fromTimeDateZD));
        fromDepartureTime.add(createTime(schedule.departures[fromIndex], fromTimeDateZD));
        alightTimesCache.add(alightTime);
        tripIds.add(schedule.tripId);

        return (fromDepartureTime.size() -1);
    }

    /**
     * Adds times to current segmentPattern as needed if they don't exist yet
     *
     * @param transitLayer transitInformation
     * @param currentPatternIdx index of current trip pattern
     * @param alightTime seconds from midnight alight time for trip we are searching for
     * @param fromTimeDateZD time and date object which is used to get date and timezone
     * @param tripIndex inside pattern
     * @see #addTime(TripPattern, int, ZonedDateTime, int)
     * @return index of time that is added or found (This is used in TransitJourneyID)
     */
    public int addTime(TransitLayer transitLayer, int currentPatternIdx, int alightTime,
        ZonedDateTime fromTimeDateZD, int tripIndex) {
        int timeIndex = 0;
        for (int patternAlightTime : alightTimesCache) {
            //If there already exists same pattern with same time we don't need to insert it again
            //We know that it is a same pattern
            if (patternAlightTime == alightTime) {
                return timeIndex;
            }
            timeIndex++;
        }
        TripPattern pattern = transitLayer.tripPatterns.get(currentPatternIdx);
        return addTime(pattern, alightTime, fromTimeDateZD, tripIndex);

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
