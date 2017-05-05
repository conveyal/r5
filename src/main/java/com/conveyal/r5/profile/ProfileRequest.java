package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.scenario.Scenario;

import java.time.*;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.SearchType;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.model.json_serialization.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import graphql.schema.DataFetchingEnvironment;

import java.io.Serializable;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;

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
    
    /** The speed of driving, in meters per second. Roads from OSM use the speed limit, this is the speed used when propagating from the street network to a pointset. */
    public float carSpeed = 11; // ~40 km/h

    /** Maximum time to reach the destination without using transit in minutes */
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

    /**
     * The maximum duration of any transit trip found by this search.
     *
     * Believe it or not the search can be quite sensitive to the value of this number, or at least whether it is set to a
     * non-infinite value, as it effectively eliminates outliers in the samples of travel time, see ticket #162.
     *
     * Consider a network in which there is significant peak-only service, that runs from say 6-8am and 4-6pm,
     * and your analysis window is 6:30 - 8:30. Suppose for the purpose of argument that there is no other way
     * to reach your destination other than this peak only transit service, and also assume that there is
     * no walking or waiting time. During your time window the you can take transit for the first 1.5 hours,
     * and after that you would have to wait until 4pm to get the first bus in the PM peak. Of course
     * no one would ever do this; the trip should be considered not possible after 8 am. The reachability threshold will
     * then determine whether this destination should be considered reachable during the time window or not.
     *
     * We had initially considered using a cutoff on wait time to board a vehicle, rather than on maximum trip duration.
     * However, the planner will cunningly work around that limit and still get on the later vehicle by riding buses the
     * wrong direction, taking long walks to transfer to other buses, etc., eventually eating up all the time until that
     * later trip. The results would then be even less clear, as they would be speckled. If the origin had no other transit
     * service, the planner would not be able to find a way to kill enough time, and the destination would be
     * considered unreachable. However, if the origin had a network of local buses that don't go to the destination, the
     * planner might be able to kill all day riding buses (without visiting the same stop twice) and still get on the late
     * afternoon vehicle.
     *
     * If we used percentiles rather than a mean, this parameter would have no effect and we could also eliminate
     * reachability thresholds. We'd just sort long or impossible trips to the top of the list (it doesn't matter whether
     * they're long or impossible; they're the same once you're past whatever travel time cutoff is being analyzed), and
     * let the percentile fall where it may; if it happens to end up in the unreachable portion, then that destination
     * is unreachable.
     *
     * Default is four hours. It should be somewhat longer than the longest travel time cutoff you wish to analyze, because
     * travel times whose "true average" (whatever that means) is near this value will be biased faster. Suppose there is a
     * trip that takes between 1.5 and 2.5 hours depending on your departure time. If you set max duration to 2 hours, the
     * average would be 1.75 hours not 2 (assuming a uniform distribution of travel times).
     *
     * This cutoff is applied to the final, propagated times at the pointset.
     *
     * It is expected that this parameter will have a significant impact on compute times in large networks as it will
     * prevent exploration to the end of the Earth.
     */
    public int maxTripDurationMinutes = 4 * 60;

    /**
     * The maximum number of rides, e.g. taking the L2 to the Red line to the Green line would be three rides.
     * Default of 6 should be enough for most intercity trips (two local buses, two intercity trains, two local buses).
     */
    public int maxRides = 8;

    /** A non-destructive scenario to apply when executing this request */
    public Scenario scenario;

    /** The ID of a scenario stored in S3. It is an error if both scenario and the scenarioId are specified */
    public String scenarioId;

    @JsonSerialize(using=ZoneIdSerializer.class)
    @JsonDeserialize(using=ZoneIdDeserializer.class)
    public ZoneId zoneId = ZoneOffset.UTC;

    //If routing with wheelchair is needed
    public boolean wheelchair;

    private SearchType searchType;

    //If this is profile or point to point route request
    private boolean profile = false;

    //If true current search is reverse search AKA we are looking for a path from destination to origin in reverse
    //It differs from searchType because it is used as egress search
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean reverseSearch = false;

    /** maximum fare. If nonnegative, fares will be used in routing. */
    public int maxFare = -1;

    /**
     * Number of Monte Carlo draws to take for frequency searches.
     *
     * We loop over all departure minutes and do a search on the scheduled portion of the network, and then while
     * holding the departure minute and scheduled search results stable, we run several Monte Carlo searches with
     * randomized frequency schedules that minute. The number of Monte Carlo draws does not need to be particularly
     * high as it happens each minute, and there is likely a lot of repetition in the scheduled service
     * (i.e. many minutes look like each other), so several minutes' Monte Carlo draws are effectively pooled.
     *
     * The algorithm divides up the number of draws into an equal number at each minute of the time window, then rounds up.
     * Note that the algorithm may actually take somewhat more draws than this, depending on the width of your time window.
     * As an extreme example, if your time window is 120 minutes and you request 121 draws, you will actually get 240, because
     * 1 &lt; 121 / 120 &lt; 2.
     */
    public int monteCarloDraws = 220;

    public boolean isProfile() {
        return profile;
    }


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
     * uses {@link #getFromTimeDateZD()}
     */
    @JsonIgnore
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
    @JsonIgnore
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

    @JsonIgnore
    public float getSpeed(StreetMode streetMode) {
        switch (streetMode) {
        case WALK:
            return walkSpeed;
        case BICYCLE:
            return bikeSpeed;
        case CAR:
            return carSpeed;
        default:
            break;
        }
        throw new IllegalArgumentException("getSpeed(): Invalid mode " + streetMode);
    }

    /**
     *
     * @return true if there is any transitMode in transitModes (Safe to call if transitModes is null)
     */
    public boolean hasTransit() {
        return this.transitModes != null && !this.transitModes.isEmpty();
    }

    /**
     * Sets profile request with parameters from GraphQL request
     * @param environment
     * @param timezone transportNetwork timezone
     * @return
     */
    public static ProfileRequest fromEnvironment(DataFetchingEnvironment environment, ZoneId timezone) {
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = timezone;

        String operation = environment.getFields().get(0).getName();

        if  (operation.equals("profile")) {
            profileRequest.profile=true;
        }



        //ZonedDatetime is used to fill fromTime/toTime and date
        //we need to have a network Timezone for that so that all the times are in network own timezone
        profileRequest.fromZonedDateTime = environment.getArgument("fromTime");
        profileRequest.toZonedDateTime = environment.getArgument("toTime");
        profileRequest.setTime();

        profileRequest.wheelchair = environment.getArgument("wheelchair");
        if (operation.equals("plan")) {
            profileRequest.searchType = environment.getArgument("searchType");
        }
        //FIXME: if any of those three values is integer not float it gets converted to java integer instead of Double (So why did we even specify type)?
        // this is needed since walkSpeed/bikeSpeed/carSpeed are GraphQLFloats which are converted to java Doubles
        double walkSpeed = environment.getArgument("walkSpeed");
        profileRequest.walkSpeed = (float) walkSpeed;
        double bikeSpeed = environment.getArgument("bikeSpeed");
        profileRequest.bikeSpeed = (float) bikeSpeed;
        double carSpeed = environment.getArgument("carSpeed");
        profileRequest.carSpeed = (float) carSpeed;
        profileRequest.streetTime = environment.getArgument("streetTime");
        profileRequest.maxWalkTime = environment.getArgument("maxWalkTime");
        profileRequest.maxBikeTime = environment.getArgument("maxBikeTime");
        profileRequest.maxCarTime = environment.getArgument("maxCarTime");
        profileRequest.minBikeTime = environment.getArgument("minBikeTime");
        profileRequest.minCarTime = environment.getArgument("minCarTime");
        profileRequest.limit = environment.getArgument("limit");

        profileRequest.suboptimalMinutes = environment.getArgument("suboptimalMinutes");
        profileRequest.bikeTrafficStress = environment.getArgument("bikeTrafficStress");
        //Bike traffic stress needs to be between 1 and 4
        if (profileRequest.bikeTrafficStress > 4) {
            profileRequest.bikeTrafficStress = 4;
        } else if (profileRequest.bikeTrafficStress < 1) {
            profileRequest.bikeTrafficStress = 1;
        }


        //This is always set otherwise GraphQL validation fails

        profileRequest.fromLat = environment.getArgument("fromLat");
        profileRequest.fromLon = environment.getArgument("fromLon");

        profileRequest.toLat = environment.getArgument("toLat");
        profileRequest.toLon = environment.getArgument("toLon");


        //Transit modes can be empty if searching for path without transit is requested
        Collection<TransitModes> transitModes = environment.getArgument("transitModes");
        if (!transitModes.isEmpty()) {
            //If there is TRANSIT mode in transit modes all of transit modes need to be added.
            if (transitModes.contains(TransitModes.TRANSIT)) {
                profileRequest.transitModes = EnumSet.allOf(TransitModes.class);
            } else {
                //Otherwise only requested modes are copied
                profileRequest.transitModes = EnumSet.copyOf(transitModes);
            }
        }
        profileRequest.accessModes = EnumSet.copyOf((Collection<LegMode>) environment.getArgument("accessModes"));
        profileRequest.egressModes = EnumSet.copyOf((Collection<LegMode>)environment.getArgument("egressModes"));


        Collection<LegMode> directModes = environment.getArgument("directModes");
        //directModes can be empty if we only want transit searches
        if (!directModes.isEmpty()) {
            profileRequest.directModes = EnumSet.copyOf(directModes);
        } else {
            profileRequest.directModes = EnumSet.noneOf(LegMode.class);
        }


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

    /**
     * Sets time and date from fromTime and toTime
     *
     * It is used in tests
     *
     * @param fromTime The beginning of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM
     * @param toTime The end of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM
     */
    public void setTime(String fromTime, String toTime) {
        fromZonedDateTime = ZonedDateTime.parse(fromTime);
        toZonedDateTime = ZonedDateTime.parse(toTime);
        setTime();
    }

    /**
     * Returns maxCar/Bike/Walk based on LegMode
     *
     * @param mode
     * @return
     */
    @JsonIgnore
    public int getTimeLimit(LegMode mode) {
        switch (mode) {
        case CAR:
            return maxCarTime * 60;
        case BICYCLE:
            return maxBikeTime * 60;
        case WALK:
            return maxWalkTime * 60;
        default:
            System.err.println("Unknown mode in getTimeLimit:"+mode.toString());
            return streetTime * 60;
        }
    }

    /**
     * Returns minCar/BikeTime based on StreetMode
     *
     * default is 0
     *
     * @param mode
     * @return min number of seconds that this mode should be used to get to stop/Park ride/bike share etc.
     */
    @JsonIgnore
    public int getMinTimeLimit(StreetMode mode) {
        switch (mode) {
        case CAR:
            return minCarTime * 60;
        case BICYCLE:
            return minBikeTime * 60;
        default:
            return 0;
        }
    }
}
