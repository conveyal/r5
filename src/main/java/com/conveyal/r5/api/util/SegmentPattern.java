package com.conveyal.r5.api.util;

import com.conveyal.gtfs.model.Service;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.vividsolutions.jts.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
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

    //TOOD: check if we really need this
    private List<Integer> alightTimes;
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
        alightTimes = new ArrayList<>();
        tripIds = new ArrayList<>();
        realTime = false;
        int patternIdx = currentPath.patterns[pathIndex];
        int boardStopIdx = currentPath.boardStops[pathIndex];
        int alightStopIdx = currentPath.alightStops[pathIndex];
        int alightTime = currentPath.alightTimes[pathIndex];
        int tripIndex = currentPath.trips[pathIndex];
        patternId = Integer.toString(patternIdx);
        this.patternIdx = patternIdx;
        fromIndex = -1;
        toIndex = -1;
        routeIndex = pattern.routeIndex;
        this.transitLayer = transitLayer;

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
        addTime(pattern, alightTime, fromTimeDateZD, tripIndex);

    }

    /**
     * Found a trip in tripPattern based on from/to indexes and alight time
     * and fills to/from arrival/Departure time arrays with time information for found trip.
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
        //We search for a trip based on provided tripPattern board, alight stop and alightTime
        //TODO: this will be removed when support for tripIDs is added into Path
        TripSchedule foundSchedule = pattern.tripSchedules.get(tripIndex);
        boolean added = false;
        final LocalDate localDate = fromTimeDateZD.toLocalDate();
        for (TripSchedule schedule: pattern.tripSchedules) {
            if (schedule.arrivals[toIndex] == alightTime) {
                Service service = transitLayer.services.get(schedule.serviceCode);
                /*Skips over trips that run during different service periods but have same times
                 *It's rare but it could happen
                 * For example we are searching for a trip on sunday but get trippattern with trips
                 * during week and sunday and found a trip that has same arrival time as trip on sunday
                 * but is on week service.
                 */
                if (!service.activeOn(localDate)) {
                    LOG.warn("Time matches but service doesn't run on wanted date! {}", service.service_id);
                    continue;
                }
                added = true;
                toArrivalTime.add(createTime(alightTime, fromTimeDateZD));
                toDepartureTime.add(createTime(schedule.departures[toIndex], fromTimeDateZD));

                fromArrivalTime.add(createTime(schedule.arrivals[fromIndex], fromTimeDateZD));
                fromDepartureTime.add(createTime(schedule.departures[fromIndex], fromTimeDateZD));
                alightTimes.add(alightTime);
                tripIds.add(schedule.tripId);
                if (!foundSchedule.tripId.equals(schedule.tripId)) {
                    LOG.error("Trip ID is not the same: {} != {}", foundSchedule.tripId, schedule.tripId);
                }
                //TODO: here is a problem if route has stop twice as start and end
                break;
            }
        }
        if (!added) {
            LOG.error("Trip with wanted time wasn't found!"
                + " Trip Id:{} Alight time:{} ({}) {} ({})",
                foundSchedule.tripId, alightTime, createTime(alightTime, fromTimeDateZD), foundSchedule.arrivals[toIndex], createTime(foundSchedule.arrivals[toIndex], fromTimeDateZD));
        }
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
        for (int patternAlightTime : alightTimes) {
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
