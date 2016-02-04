package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.BoardingAssumption;
import com.conveyal.r5.analyst.scenario.Scenario;

import java.time.*;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.model.json_serialization.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import graphql.schema.DataFetchingEnvironment;

import java.io.Serializable;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;

/**
 * All the modifiable parameters for profile routing.
 */
public class ProfileRequest implements Serializable, Cloneable {
    private static final long serialVersionUID = -6501962907644662303L;

    //From and to zonedDateTime filled in GraphQL request
    //Based on those two variables and timezone from/totime and date is filled
    //Since from/to time and date is assumed to be in local timezone AKA same timezone as TransportNetwork
    private ZonedDateTime fromZonedDateTime;

    private ZonedDateTime toZonedDateTime;

    /** The latitude of the origin. */
    public double fromLat;
    
    /** The longitude of the origin. */
    public double fromLon;
    
    /** The latitude of the destination. Must be set even in Analyst mode. */
    public double toLat;
    
    /** The longitude of the destination. Must be set even in Analyst mode. */
    public double toLon;
    
    /** The beginning of the departure window, in seconds since midnight. */
    public int    fromTime;
    
    /** The end of the departure window, in seconds since midnight. */
    public int    toTime;

    /** The speed of walking, in meters per second */
    public float  walkSpeed = 1.3f;
    
    /** The speed of cycling, in meters per second */
    public float  bikeSpeed = 4f;

    /** maximum level of traffic stress for cycling, 1 - 4 */
    public int bikeTrafficStress = 4;
    
    /** The speed of driving, in meters per second */
    public float  carSpeed;

    /** Maximum time to reach the destination without using transit */
    public int    streetTime = 60;
    
    /**
     * Maximum walk time before and after using transit, in minutes
     *
     * NB the time to reach the destination after leaving transit is considered separately from the time to reach
     * transit at the start of the search; e.g. if you set maxWalkTime to 600 (ten minutes), you could potentially walk
     * up to ten minutes to reach transit, and up to _another_ ten minutes to reach the destination after leaving transit.
     *
     * This is required because hard resource limiting on non-objective variables is algorithmically invalid. Consider
     * a case where there is a destination 10 minutes from transit and an origin 5 minutes walk from a feeder bus and
     * 15 minutes walk from a fast train, and the walk budget is 20 minutes. If an intermediate point in the search
     * (for example, a transfer station) is reached by the fast train before it is reached by the bus, the route using
     * the bus will be dominated. When we leave transit, though, we have already used up 15 minutes of our walk budget
     * and don't have enough remaining to reach the destination.
     *
     * This is solved by using separate walk budgets at the origin and destination. It could also be solved (although this
     * would slow the algorithm down) by retaining all Pareto-optimal combinations of (travel time, walk distance).
     */
    public int    maxWalkTime = 30;
    
    /** Maximum bike time when using transit */
    public int    maxBikeTime = 30;
    
    /** Maximum car time before when using transit */ 
    public int    maxCarTime = 30;
    
    /** Minimum time to ride a bike (to prevent extremely short bike legs) */
    public int    minBikeTime = 5;
    
    /** Minimum time to drive (to prevent extremely short driving legs) */
    public int    minCarTime = 5;

    /** The date of the search */
    public LocalDate date;
    
    /** the maximum number of options presented PER ACCESS MODE */
    public int limit;
    
    /** The modes used to access transit */
    @JsonSerialize(using = LegModeSetSerializer.class)
    @JsonDeserialize(using = LegModeSetDeserializer.class)
    public EnumSet<LegMode> accessModes;
    
    /** The modes used to reach the destination after leaving transit */
    @JsonSerialize(using = LegModeSetSerializer.class)
    @JsonDeserialize(using = LegModeSetDeserializer.class)
    public EnumSet<LegMode> egressModes;
    
    /** The modes used to reach the destination without transit */
    @JsonSerialize(using = LegModeSetSerializer.class)
    @JsonDeserialize(using = LegModeSetDeserializer.class)
    public EnumSet<LegMode> directModes;
    
    /** The transit modes used */
    @JsonSerialize(using = TransitModeSetSerializer.class)
    @JsonDeserialize(using = TransitModeSetDeserializer.class)
    public EnumSet<TransitModes> transitModes;
    
    /** If true, disable all goal direction and propagate results to the street network */
    public boolean analyst = false;

    /**
     * What is the minimum proportion of the time for which a destination must be accessible for it to be included in
     * the average?
     *
     * This avoids issues where destinations are reachable for some very small percentage of the time, either because
     * there is a single departure near the start of the time window, or because they take approximately 2 hours
     * (the default maximum cutoff) to reach.

     * Consider a search run with time window 7AM to 9AM, and an origin and destination connected by an express
     * bus that runs once at 7:05. For the first five minutes of the time window, accessibility is very good.
     * For the rest, there is no accessibility; if we didn't have this rule in place, the average would be the average
     * of the time the destination is reachable, and the time it is unreachable would be excluded from the calculation
     * (see issue 2148)

     * There is another issue that this rule does not completely address. Consider a trip that takes 1:45
     * exclusive of wait time and runs every half-hour. Half the time it takes less than two hours and is considered
     * and half the time it takes more than two hours and is excluded, so the average is biased low on very long trips.
     * This rule catches the most egregious cases (say where we average only the best four minutes out of a two-hour
     * span) but does not completely address the issue. However if you're looking at a time cutoff significantly
     * less than two hours, it's not a big problem. Significantly less is half the headway of your least-frequent service, because
     * if there is a trip on your least-frequent service that takes on average the time cutoff plus one minute
     * it will be unbiased and considered unreachable iff the longest trip is less than two hours, which it has
     * to be if the time cutoff plus half the headway is less than two hours, assuming a symmetric travel time
     *
     * The default is 0.5.
     */
    public float reachabilityThreshold = 0.5f;

    /** Deprecated: code now always does a monte carlo simulation */
    @Deprecated
    public BoardingAssumption boardingAssumption;

    /* The relative importance of different factors when biking */
    /** The relative importance of maximizing safety when cycling */
    public int bikeSafe;
    
    /** The relative importance of minimizing hills when cycling */
    public int bikeSlope;
    
    /** The relative importance of minimizing time when cycling */
    public int bikeTime;
    // FIXME change "safe" to "danger" to consistently refer to the things being minimized

    /**
      This parameter compensates for the fact that GTFS does not contain information about schedule deviation (lateness).
      The min-max travel time range for some trains is zero, since the trips are reported to always have the same
      timings in the schedule. Such an option does not overlap (temporally) its alternatives, and is too easily
      eliminated by an alternative that is only marginally better. We want to effectively push the max travel time of
      alternatives out a bit to account for the fact that they don't always run on schedule.
    */
    public int suboptimalMinutes = 5;

    /** A non-destructive scenario to apply when executing this request */
    public Scenario scenario;

    @JsonSerialize(using=ZoneIdSerializer.class)
    @JsonDeserialize(using=ZoneIdDeserializer.class)
    public ZoneId zoneId = ZoneOffset.UTC;

    public ProfileRequest clone () {
        try {
            return (ProfileRequest) super.clone();
        } catch (CloneNotSupportedException e) {
            // checked clonenotsupportedexception is about the stupidest thing in java
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns number of milliseconds UNIX time made with date and fromTime
     *
     * It reads date as date in transportNetwork timezone when it is converted to UNIX time it is in UTC
     *
     * It needs to be decided how to do this correctly: #37
     *
     * If date isn't set current date is used. Time is empty (one hour before midnight in UTC if +1 timezone is used)
     *
     * uses {@link this#getFromTimeDateZD()}
     */
    public long getFromTimeDate() {
        return getFromTimeDateZD().toInstant().toEpochMilli();
    }

    /**
     * Returns ZonedDateTime made with date and fromTime fields
     *
     * It reads date as date in transportNetwork timezone when it is converted to UNIX time it is in UTC
     *
     * It needs to be decided how to do this correctly: #37
     *
     * If date isn't set current date is used. Time is empty (one hour before midnight in UTC if +1 timezone is used)
     */
    public ZonedDateTime getFromTimeDateZD() {
        ZonedDateTime currentDateTime;

        if (date == null) {
            currentDateTime = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.DAYS);
        } else {
            currentDateTime = ZonedDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), 0,0,0,0,zoneId);
        }

        //fromTime is in seconds and there are 1000 ms in a second
        return  currentDateTime.plusSeconds(fromTime);
    }

    public float getSpeed(Mode mode) {
        switch (mode) {
        case WALK:
            return walkSpeed;
        case BICYCLE:
            return bikeSpeed;
        case CAR:
            return carSpeed;
        default:
            break;
        }
        throw new IllegalArgumentException("getSpeed(): Invalid mode " + mode);
    }

    /**
     *
     * @return true if there is any transitMode in transitModes (Safe to call if transitModes is null)
     */
    public boolean useTransit() {
        return this.transitModes != null && !this.transitModes.isEmpty();
    }

    /**
     * Sets profile request with parameters from GraphQL request
     * @param environment
     * @param timezone transportNetwork timezone
     * @return
     */
    public static ProfileRequest fromEnvironment(DataFetchingEnvironment environment,
        ZoneId timezone) {
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = timezone;

        //This is always set otherwise GraphQL validation fails
        HashMap<String, Float> fromCoordinate = environment.getArgument("from");
        HashMap<String, Float> toCoordinate = environment.getArgument("to");

        //ZonedDatetime is used to fill fromTime/toTime and date
        //we need to have a network Timezone for that so that all the times are in network own timezone
        profileRequest.fromZonedDateTime = environment.getArgument("fromTime");
        profileRequest.toZonedDateTime = environment.getArgument("toTime");
        profileRequest.setTime();


        profileRequest.fromLon = fromCoordinate.get("lon");
        profileRequest.fromLat = fromCoordinate.get("lat");

        profileRequest.toLat = toCoordinate.get("lat");
        profileRequest.toLon = toCoordinate.get("lon");

        profileRequest.transitModes = EnumSet.copyOf((Collection<TransitModes>) environment.getArgument("transitModes"));
        profileRequest.accessModes = EnumSet.copyOf((Collection<LegMode>) environment.getArgument("accessModes"));
        profileRequest.egressModes = EnumSet.copyOf((Collection<LegMode>)environment.getArgument("egressModes"));

        profileRequest.directModes = EnumSet.copyOf((Collection<LegMode>)environment.getArgument("directModes"));


        return profileRequest;
    }

    /**
     * Converts from/to zonedDateTime to graph timezone and fill from/totime and date
     */
    private void setTime() {
        if (fromZonedDateTime != null) {
            fromZonedDateTime = fromZonedDateTime.withZoneSameInstant(zoneId);
            fromTime = fromZonedDateTime.getHour()*3600+fromZonedDateTime.getMinute()*60+fromZonedDateTime.getSecond();
            date = fromZonedDateTime.toLocalDate();
        }
        if (toZonedDateTime != null) {
            toZonedDateTime = toZonedDateTime.withZoneSameInstant(zoneId);
            toTime = toZonedDateTime.getHour() * 3600 + toZonedDateTime.getMinute() * 60
                + toZonedDateTime.getSecond();
            date = toZonedDateTime.toLocalDate();
        }
    }
}
