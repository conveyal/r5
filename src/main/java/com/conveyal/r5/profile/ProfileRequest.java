package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
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

    // Analyst used to serialize the request along with regional analysis results into MapDB.
    // In Analysis, the request is still saved but as JSON in MongoDB.
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
    public float carSpeed = 2.22f; // ~8 km/h

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
    
    /** Maximum bike time when using transit, in minutes */
    public int    maxBikeTime = 30;
    
    /** Maximum car time before when using transit, in minutes */
    public int    maxCarTime = 30;
    
    /** Minimum time to ride a bike (to prevent extremely short bike legs), in minutes */
    public int    minBikeTime = 5;
    
    /** Minimum time to drive (to prevent extremely short driving legs), in minutes */
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
     * This parameter compensates for the fact that GTFS does not contain information about schedule deviation (lateness).
     * The min-max travel time range for some trains is zero, since the trips are reported to always have the same
     * timings in the schedule. Such an option does not overlap (temporally) its alternatives, and is too easily
     * eliminated by an alternative that is only marginally better. We want to effectively push the max travel time of
     * alternatives out a bit to account for the fact that they don't always run on schedule.
     */
    public int suboptimalMinutes = 5;

    /**
     * The maximum duration of any transit trip found by this search.
     */
    public int maxTripDurationMinutes = 2 * 60;

    /**
     * The maximum number of rides, e.g. taking the L2 to the Red line to the Green line would be three rides.
     */
    public int maxRides = 8;

    /** A non-destructive scenario to apply when executing this request */
    public Scenario scenario;

    /**
     * The ID of a scenario stored in S3 as JSON.
     * You must specify one (but only one) of scenario or scenarioId.
     * It is an error if both scenario and the scenarioId are specified.
     */
    public String scenarioId;

    @JsonSerialize(using=ZoneIdSerializer.class)
    @JsonDeserialize(using=ZoneIdDeserializer.class)
    public ZoneId zoneId = ZoneOffset.UTC;

    /**
     * Require all streets and transit to be wheelchair-accessible. This is not fully implemented and doesn't work
     * at all in Analysis (FastRaptorWorker), only in Modeify (McRaptorSuboptimalPathProfileRouter).
     */
    public boolean wheelchair;

    /** Whether this is a depart-after or arrive-by search */
    private SearchType searchType;

    /**
     * If true current search is reverse search AKA we are looking for a path from destination to origin in reverse
     * It differs from searchType because it is used as egress search.  Note that NON_DEFAULT allows the broker to
     * talk to older workers.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean reverseSearch = false;

    /**
     * Maximum fare for constraining monetary cost of paths during search in Analysis.
     * If nonnegative, fares will be used in routing.
     */
    public int maxFare = -1;

    /**
     * An object that should have at a minimum a "type" set according to the list in InRoutingFareCalculator.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public InRoutingFareCalculator inRoutingFareCalculator = null;

    /**
     * Number of Monte Carlo draws to take for frequency searches.
     *
     * We loop over all departure minutes and do a search on the scheduled portion of the network, and then while
     * holding the departure minute and scheduled search results stable, we run several Monte Carlo searches with
     * randomized frequency schedules that minute. The number of Monte Carlo draws does not need to be particularly
     * high as it happens each minute, and there is likely a lot of repetition in the scheduled service
     * (i.e. many minutes look like each other), so several minutes' Monte Carlo draws are effectively pooled.
     *
     * FastRaptorWorker divides up the number of draws into an equal number at each minute of the time window, then
     * rounds up. Note that the algorithm may actually take somewhat more draws than this, depending on the width of
     * your time window. As an extreme example, if your time window is 120 minutes and you request 121 draws, you
     * will actually get 240, because 1 &lt; 121 / 120 &lt; 2.
     *
     * McRaptor worker samples departure times, repeating a random walk over the departure time window until a sample
     * with exactly this number of departure times is generated.
     */
    public int monteCarloDraws = 220;

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
     * It reads date as date in transportNetwork timezone when it is converted to UNIX time it is in UTC
     * It needs to be decided how to do this correctly: #37
     * If date isn't set current date is used. Time is empty (one hour before midnight in UTC if +1 timezone is used)
     * uses {@link #getFromTimeDateZD()}
     */
    @JsonIgnore
    public long getFromTimeDate() {
        return getFromTimeDateZD().toInstant().toEpochMilli();
    }

    /**
     * Returns ZonedDateTime made with date and fromTime fields
     * It reads date as date in transportNetwork timezone when it is converted to UNIX time it is in UTC
     * It needs to be decided how to do this correctly: #37
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

    /**
     * @return the speed at which the given mode will traverse street edges, in floating point meters per second.
     */
    @JsonIgnore
    public float getSpeedForMode (StreetMode streetMode) {
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
        throw new IllegalArgumentException("getSpeedForMode(): Invalid mode " + streetMode);
    }

    /**
     * @return the maximum pre-transit travel time for the given mode in minutes
     */
    @JsonIgnore
    public int getMaxAccessTimeForMode (StreetMode mode) {
        switch (mode) {
            case CAR:
                return maxCarTime;
            case BICYCLE:
                return maxBikeTime;
            case WALK:
                return maxWalkTime;
            default:
                throw new IllegalArgumentException("Invalid mode");
        }
    }

    /**
     * @return true if there is any transitMode in transitModes (Safe to call if transitModes is null)
     */
    public boolean hasTransit() {
        return this.transitModes != null && !this.transitModes.isEmpty();
    }

    /**
     * Factory method to create a profile request with query parameters from a GraphQL request
     */
    public static ProfileRequest fromGraphqlEnvironment(DataFetchingEnvironment environment, ZoneId timezone) {
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = timezone;

        String operation = environment.getFields().get(0).getName();
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

    /** Return the length of the time window in minutes */
    @JsonIgnore
    public int getTimeWindowLengthMinutes() {
        return (toTime - fromTime) / 60;
    }

    /**
     * Return the number of Monte Carlo draws that must be done each minute to get at least the desired number of total
     * Monte Carlo draws over all minutes.
     */
    @JsonIgnore
    public int getMonteCarloDrawsPerMinute() {
        return (int) Math.ceil((double) monteCarloDraws / getTimeWindowLengthMinutes());
    }
}
