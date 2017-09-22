package com.conveyal.r5;

import com.conveyal.r5.api.util.*;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.model.json_serialization.PolyUtil;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.fare.RideType;
import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.GraphQLException;
import graphql.Scalars;
import graphql.language.StringValue;
import graphql.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Created by mabu on 30.10.2015.
 */
public class GraphQLSchema {
    private static final Logger LOG = LoggerFactory.getLogger(GraphQLSchema.class);

    public static GraphQLEnumType locationTypeEnum = GraphQLEnumType.newEnum()
        .name("LocationType")
        .description("Identifies whether this stop represents a stop or station.")
        .value("STOP", 0, "A location where passengers board or disembark from a transit vehicle.")
        .value("STATION", 1, "A physical structure or area that contains one or more stop.")
        .value("ENTRANCE", 2)
        .build();

    public static GraphQLEnumType wheelchairBoardingEnum = GraphQLEnumType.newEnum()
        .name("WheelchairBoarding")
        .value("NO_INFORMATION", 0, "There is no accessibility information for the stop.")
        .value("POSSIBLE", 1, "At least some vehicles at this stop can be boarded by a rider in a wheelchair.")
        .value("NOT_POSSIBLE", 2, "Wheelchair boarding is not possible at this stop.")
        .build();

    public static GraphQLEnumType bikesAllowedEnum = GraphQLEnumType.newEnum()
        .name("BikesAllowed")
        .value("NO_INFORMATION", 0, "There is no bike information for the trip.")
        .value("ALLOWED", 1, "The vehicle being used on this particular trip can accommodate at least one bicycle.")
        .value("NOT_ALLOWED", 2, "No bicycles are allowed on this trip.")
        .build();

    public static GraphQLEnumType relativeDirectionEnum = GraphQLEnumType.newEnum()
        .name("RelativeDirection")
        .description("Represents a turn direction, relative to the current heading.")
        .value("DEPART", RelativeDirection.DEPART)
        .value("HARD_LEFT", RelativeDirection.HARD_LEFT)
        .value("LEFT", RelativeDirection.LEFT)
        .value("SLIGHTLY_LEFT", RelativeDirection.SLIGHTLY_LEFT)
        .value("CONTINUE", RelativeDirection.CONTINUE)
        .value("SLIGHTLY_RIGHT", RelativeDirection.SLIGHTLY_RIGHT)
        .value("RIGHT", RelativeDirection.RIGHT)
        .value("HARD_RIGHT", RelativeDirection.HARD_RIGHT)
        .value("CIRCLE_CLOCKWISE", RelativeDirection.CIRCLE_CLOCKWISE, "traffic circle in left driving countries")
        .value("CIRCLE_COUNTERCLOCKWISE", RelativeDirection.CIRCLE_COUNTERCLOCKWISE, "traffic circle in right driving countries")
        .value("ELEVATOR", RelativeDirection.ELEVATOR)
        .value("UTURN_LEFT", RelativeDirection.UTURN_LEFT)
        .value("UTURN_RIGHT", RelativeDirection.UTURN_RIGHT)
        .build();

    public static GraphQLEnumType absoluteDirectionEnum = GraphQLEnumType.newEnum()
        .name("AbsoluteDirection")
        .description("An absolute cardinal or intermediate direction.")
        .value("NORTH", AbsoluteDirection.NORTH)
        .value("NORTHEAST", AbsoluteDirection.NORTHEAST)
        .value("EAST", AbsoluteDirection.EAST)
        .value("SOUTHEAST", AbsoluteDirection.SOUTHEAST)
        .value("SOUTH", AbsoluteDirection.SOUTH)
        .value("SOUTHWEST", AbsoluteDirection.SOUTHWEST)
        .value("WEST", AbsoluteDirection.WEST)
        .value("NORTHWEST", AbsoluteDirection.NORTHWEST)
        .build();

    public static GraphQLEnumType nonTransitModeEnum = GraphQLEnumType.newEnum()
        .name("NonTransitMode")
        .description("Modes of transportation that aren't public transit")
        .value("WALK", NonTransitMode.WALK)
        .value("BICYCLE", NonTransitMode.BICYCLE)
        .value("CAR", NonTransitMode.CAR)
        .build();

    //This is used in streetSegments and is an union of accessLegModeEnum and otherLegModeEnum
    public static GraphQLEnumType legModeEnum = GraphQLEnumType.newEnum()
        .name("LegMode")
        .description("Modes of transport on ingress egress legs")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .value("CAR_PARK", LegMode.CAR_PARK, "Park & Ride")
        .build();

    //LegMode enum is splitted into multiple GraphQL legEnums so that we can get validation on input by GraphQL for free
    //Because some modes can appear only on access leg (CAR_PARK, BIKE_PARK), and some on egress and direct
    public static GraphQLEnumType accessLegModeEnum = GraphQLEnumType.newEnum()
        .name("AccessLegMode")
        .description("Modes of transport on ingress legs")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .value("CAR_PARK", LegMode.CAR_PARK, "Park & Ride")
        .build();

    public static GraphQLEnumType otherLegModeEnum = GraphQLEnumType.newEnum()
        .name("OtherLegMode")
        .description("Modes of transport on egress legs and directModes")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .build();

    public static GraphQLEnumType transitmodeEnum = GraphQLEnumType.newEnum()
        .name("TransitModes")
        .description("Types of transit mode transport from GTFS")
        .value("TRAM", TransitModes.TRAM,
            " Tram, Streetcar, Light rail. Any light rail or street level system within a metropolitan area.")
        .value("SUBWAY", TransitModes.SUBWAY,
            "Subway, Metro. Any underground rail system within a metropolitan area.")
        .value("RAIL", TransitModes.RAIL, "Rail. Used for intercity or long-distance travel.")
        .value("BUS", TransitModes.BUS, "Bus. Used for short- and long-distance bus routes.")
        .value("FERRY", TransitModes.FERRY,
            "Ferry. Used for short- and long-distance boat service.")
        .value("CABLE_CAR", TransitModes.CABLE_CAR, "Cable car. Used for street-level cable cars where the cable runs beneath the car.")
        .value("GONDOLA", TransitModes.GONDOLA, " Gondola, Suspended cable car. Typically used for aerial cable cars where the car is suspended from the cable.")
        .value("FUNICULAR", TransitModes.FUNICULAR, "Funicular. Any rail system designed for steep inclines.")
        .value("TRANSIT", TransitModes.TRANSIT, "All transit modes")
        .build();

    public static GraphQLEnumType searchTypeEnum = GraphQLEnumType.newEnum()
        .name("SearchType")
        .description("Type of plan search")
        .value("ARRIVE_BY", SearchType.ARRIVE_BY,
            "Search is made for trip that needs to arrive at specific time/date")
        .value("DEPART_FROM", SearchType.DEPART_FROM,
            "Search is made for a trip that needs to depart at specific time/date")
        .build();

    public static GraphQLEnumType rideTypeEnum = GraphQLEnumType.newEnum()
        .name("RideType")
        .description("Type of Ride in Fare. (Currently only DC Metro)")
        .value("METRO_RAIL", RideType.METRO_RAIL)
        .value("METRO_BUS_LOCAL", RideType.METRO_BUS_LOCAL)
        .value("METRO_BUS_EXPRESS", RideType.METRO_BUS_EXPRESS)
        .value("METRO_BUS_AIRPORT", RideType.METRO_BUS_AIRPORT)
        .value("DC_CIRCULATOR_BUS", RideType.DC_CIRCULATOR_BUS)
        .value("ART_BUS", RideType.ART_BUS)
        .value("DASH_BUS", RideType.DASH_BUS)
        .value("MARC_RAIL", RideType.MARC_RAIL)
        .value("MTA_BUS_LOCAL", RideType.MTA_BUS_LOCAL)
        .value("MTA_BUS_EXPRESS", RideType.MTA_BUS_EXPRESS)
        .value("MTA_BUS_COMMUTER", RideType.MTA_BUS_COMMUTER)
        .value("VRE_RAIL", RideType.VRE_RAIL)
        .value("MCRO_BUS_LOCAL", RideType.MCRO_BUS_LOCAL)
        .value("MCRO_BUS_EXPRESS", RideType.MCRO_BUS_EXPRESS)
        .value("FAIRFAX_CONNECTOR_BUS", RideType.FAIRFAX_CONNECTOR_BUS)
        .value("PRTC_BUS", RideType.PRTC_BUS)
        .build();


    //Input only for now
    public static GraphQLScalarType GraphQLLocalDate = new GraphQLScalarType("LocalDate",
        "Java 8 LocalDate type YYYY-MM-DD", new Coercing() {
        @Override
        public Object serialize(Object input) {
            LOG.info("Date coerce:{}", input);
            if (input instanceof String) {
                try {
                    if (input.equals("today")) {
                        return LocalDate.now();
                    }
                    return LocalDate
                        .parse((String) input, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (Exception e) {
                    throw new GraphQLException("Problem parsing date (Expected format is YYYY-MM-DD): " + e.getMessage());
                }
            } else  if (input instanceof LocalDate) {
                return input;
            } else {
                throw new GraphQLException("Invalid date input. Expected String or LocalDate");
            }
        }

        @Override
        public Object parseValue(Object input) {
            return serialize(input);
        }

        @Override
        public Object parseLiteral(Object input) {
            //Seems to be used in querying
            LOG.info("Date coerce literal:{}", input);
            if (!(input instanceof StringValue)) {
                return null;
            }
            try {
                String sinput = ((StringValue) input).getValue();
                if (sinput.equals("today")) {
                    return LocalDate.now();
                }
                return LocalDate
                    .parse(sinput, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                throw new GraphQLException("Problem parsing date (Expected format is YYYY-MM-DD): " + e.getMessage());
            }
        }
    });

    //Output type for now
    //FIXME: ISO8601 parsing and outputting in java is little broken it doesn't support timezone as +HH+MM
    //https://stackoverflow.com/questions/32079459/java-time-zoneddatetime-parse-and-iso8601
    public static GraphQLScalarType GraphQLZonedDateTime = new GraphQLScalarType("ZonedDateTime",
        "Java 8 ZonedDateTime type ISO 8601 YYYY-MM-DDTHH:MM:SS+HH:MM", new Coercing() {
        @Override
        public Object serialize(Object input) {
            //LOG.info("TDate coerce:{}", input);
            if (input instanceof String) {
                try {

                    return ZonedDateTime
                        .parse((String) input, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (Exception e) {
                    throw new GraphQLException("Problem parsing date (Expected format is ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM): " + e.getMessage());
                }
            } else  if (input instanceof ZonedDateTime) {
                return ((ZonedDateTime) input).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } else {
                throw new GraphQLException("Invalid date input. Expected String or LocalDate");
            }
        }

        @Override
        public Object parseValue(Object input) {
            return serialize(input);
        }

        @Override
        public Object parseLiteral(Object input) {
            //Seems to be used in querying
            //LOG.info("TDate coerce literal:{}", input);
            if (!(input instanceof StringValue)) {
                return null;
            }
            try {
                String sinput = ((StringValue) input).getValue();

                return ZonedDateTime
                    .parse(sinput, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception e) {
                throw new GraphQLException("Problem parsing date (Expected format is ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM): " + e.getMessage());
            }
        }
    });


    public GraphQLOutputType profileResponseType = new GraphQLTypeReference("Profile");

    public GraphQLOutputType profileOptionType = new GraphQLTypeReference("ProfileOption");

    public GraphQLOutputType routeType = new GraphQLTypeReference("Route");

    public GraphQLOutputType stopType = new GraphQLTypeReference("Stop");

    public GraphQLOutputType stopClusterType = new GraphQLTypeReference("StopCluster");

    public GraphQLOutputType fareType = new GraphQLTypeReference("Fare");

    public GraphQLOutputType statsType = new GraphQLTypeReference("Stats");

    public GraphQLOutputType polylineGeometryType = new GraphQLTypeReference("PolylineGeometry");

    public GraphQLOutputType streetEdgeInfoType = new GraphQLTypeReference("StreetEdgeInfo");

    public GraphQLOutputType streetSegmentType = new GraphQLTypeReference("StreetSegment");

    public GraphQLOutputType transitSegmentType = new GraphQLTypeReference("TransitSegment");

    public GraphQLOutputType segmentPatternType = new GraphQLTypeReference("SegmentPattern");

    public GraphQLOutputType bikeRentalStationType = new GraphQLTypeReference("BikeRentalStation");

    public GraphQLOutputType elevationType = new GraphQLTypeReference("Elevation");

    public GraphQLOutputType alertType = new GraphQLTypeReference("Alert");

    public GraphQLOutputType transitJourneyIDType = new GraphQLTypeReference("TransitJourneyID");

    public GraphQLOutputType pointToPointConnectionType = new GraphQLTypeReference("PointToPointConnection");

    public GraphQLOutputType itineraryType = new GraphQLTypeReference("Itinerary");

    public GraphQLOutputType tripPatternType = new GraphQLTypeReference("TripPattern");

    public GraphQLOutputType tripType = new GraphQLTypeReference("Trip");

    public GraphQLOutputType parkRideParkingType = new GraphQLTypeReference("ParkRideParking");

    // @formatter:off


    public GraphQLObjectType queryType;

    public graphql.schema.GraphQLSchema indexSchema;

    private GraphQLArgument stringTemplate(String name, String defaultValue) {
        GraphQLArgument.Builder argument = GraphQLArgument.newArgument().name(name).type(
            Scalars.GraphQLString);
        if (defaultValue != null) {
            argument.defaultValue(defaultValue);
        }
        return argument.build();
    }

    //TODO: code generation with:
    // http://sculptorgenerator.org/
    //https://github.com/square/javapoet
    //https://github.com/javaparser/javaparser https://github.com/musiKk/plyj
    //spark


    public GraphQLSchema(PointToPointQuery profileResponse) {

        fareType = GraphQLObjectType.newObject()
            .name("Fare")
            .description("Fare for transit")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("type")
                .type(rideTypeEnum)
                .dataFetcher(environment -> ((Fare) environment.getSource()).type)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("low")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((Fare) environment.getSource()).low)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("peak")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((Fare) environment.getSource()).peak)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("senior")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((Fare) environment.getSource()).senior)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transferReduction")
                .type(Scalars.GraphQLBoolean)
                .dataFetcher(environment -> ((Fare) environment.getSource()).transferReduction)
                .build())
            //FIXME: This is temporary always USD since only Washington DC fares are currently supported
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("currency")
                .description("In which currency is the fare in ISO 4217 code")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .staticValue("USD")
                .build())
            .build();

        stopType = GraphQLObjectType.newObject()
            .name("Stop")
            .description("Transit stop")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stopId")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("GTFS Stop ID")
                .dataFetcher(environment -> ((Stop) environment.getSource()).stopId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("name")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Stop name")
                .dataFetcher(environment -> ((Stop) environment.getSource()).name)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Latitude")
                .dataFetcher(environment -> ((Stop) environment.getSource()).lat)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Longitude")
                .dataFetcher(environment -> ((Stop) environment.getSource()).lon)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("code")
                .type(Scalars.GraphQLString)
                .description("Short text or number that identifies this stop to passengers")
                .dataFetcher(environment -> ((Stop) environment.getSource()).code)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("zoneId")
                .type(Scalars.GraphQLString)
                .description("Fare zone for stop")
                .dataFetcher(environment -> ((Stop) environment.getSource()).zoneId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("wheelchairBoarding")
                .type(Scalars.GraphQLBoolean)
                .dataFetcher(environment -> ((Stop) environment.getSource()).wheelchairBoarding)
                .build())
            .build();

        stopClusterType = GraphQLObjectType.newObject()
            .name("StopCluster")
            .description("Groups stops by geographic proximity and name similarity.")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("id")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Internal ID of stop cluster")
                .dataFetcher(environment -> ((StopCluster) environment.getSource()).id)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("name")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Name of first stop in a cluster")
                .dataFetcher(environment -> ((StopCluster) environment.getSource()).name)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lon")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((StopCluster) environment.getSource()).lon)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lat")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((StopCluster) environment.getSource()).lat)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stops")
                .description("Stops in a cluster")
                .type(new GraphQLList(stopType))
                .dataFetcher(environment -> ((StopCluster) environment.getSource()).stops)
                .build())
            .build();

        parkRideParkingType = GraphQLObjectType.newObject()
            .name("ParkRideParking")
            .description("Information about P+R parking lots")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("id")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Unique ID for this P+R can change in each data rebuild")
                .dataFetcher(environment -> ((ParkRideParking) environment.getSource()).id)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("name")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> ((ParkRideParking) environment.getSource()).name)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("capacity")
                .type(Scalars.GraphQLInt)
                .description("Number of all spaces")
                .dataFetcher(environment -> ((ParkRideParking) environment.getSource()).capacity)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lon")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((ParkRideParking) environment.getSource()).lon)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lat")
                .type(Scalars.GraphQLFloat)
                .dataFetcher(environment -> ((ParkRideParking) environment.getSource()).lat)
                .build())
            .build();

        statsType = GraphQLObjectType.newObject()
            .name("Stats")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("min")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Minimum travel time (seconds)")
                .dataFetcher(environment -> ((Stats) environment.getSource()).min)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("avg")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Average travel time (including waiting) (seconds)")
                .dataFetcher(environment -> ((Stats) environment.getSource()).avg)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("max")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Maximum travel time (seconds)")
                .dataFetcher(environment -> ((Stats) environment.getSource()).max)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("num")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("number of options")
                .dataFetcher(environment -> ((Stats) environment.getSource()).num)
                .build())
            .build();

        polylineGeometryType = GraphQLObjectType.newObject()
            .name("PolylineGeometry")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("points")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Polyline encoded geometry")
                .dataFetcher(environment -> ((PolylineGeometry) environment.getSource()).points)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("length")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Length of polyline encoded geometry")
                .dataFetcher(environment -> ((PolylineGeometry) environment.getSource()).length)
                .build())
            .build();

        routeType = GraphQLObjectType.newObject()
            .name("Route")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("id")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Route ID")
                .dataFetcher(environment -> ((Route) environment.getSource()).id)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routeIdx")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Transport network unique integer ID of route")
                .dataFetcher(environment -> ((Route) environment.getSource()).routeIdx)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("shortName")
                .type(Scalars.GraphQLString)
                .description("Short name of the route. Usually number or number plus letter")
                .dataFetcher(environment -> ((Route) environment.getSource()).shortName)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("longName")
                .type(Scalars.GraphQLString)
                .description("Full, more descriptive name of the route")
                .dataFetcher(environment -> ((Route) environment.getSource()).longName)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("description")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> ((Route) environment.getSource()).description)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("mode")
                .type(new GraphQLNonNull(transitmodeEnum))
                .description("Type of transportation used on a route")
                .dataFetcher(environment -> ((Route) environment.getSource()).mode)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routeColor")
                .type(Scalars.GraphQLString)
                .description("Color that corresponds to a route (it needs to be character hexadecimal number) (00FFFF)")
                .dataFetcher(environment -> ((Route) environment.getSource()).routeColor)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("textColor")
                .type(Scalars.GraphQLString)
                .description("Color that is used for text in route (it needs to be character hexadecimal number)")
                .dataFetcher(environment -> ((Route) environment.getSource()).textColor)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("url")
                .type(Scalars.GraphQLString)
                .description("URL with information about route")
                .dataFetcher(environment -> ((Route) environment.getSource()).url)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("agencyName")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Full name of the transit agency for this route")
                .dataFetcher(environment -> ((Route) environment.getSource()).agencyName)
                .build())
            .build();

        bikeRentalStationType = GraphQLObjectType.newObject()
            .name("BikeRentalStation")
            .description("Information about Bike rental station")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("id")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).id)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("name")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).name)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Coordinates")
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).lat)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("lon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Coordinates")
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).lon)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("bikesAvailable")
                .type(Scalars.GraphQLInt)
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).bikesAvailable)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("spacesAvailable")
                .type(Scalars.GraphQLInt)
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).spacesAvailable)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("allowDropoff")
                .type(Scalars.GraphQLBoolean)
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).allowDropoff)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("networks")
                .type(new GraphQLList(Scalars.GraphQLString))
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).networks)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("realTimeData")
                .type(Scalars.GraphQLBoolean)
                .dataFetcher(environment -> ((BikeRentalStation) environment.getSource()).realTimeData)
                .build())
            .build();

        tripType = GraphQLObjectType.newObject()
            .name("Trip")
            .description("Information about one trip")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("tripId")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("GTFS trip ID (Agency unique)")
                .dataFetcher(environment -> ((Trip) environment.getSource()).tripId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("serviceId")
                .type(Scalars.GraphQLString)
                .description("Generated Service ID")
                .dataFetcher(environment -> ((Trip) environment.getSource()).serviceId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("wheelchairAccessible")
                .type(new GraphQLNonNull(Scalars.GraphQLBoolean))
                .description("If this trip can be used with wheelchair")
                .dataFetcher(environment -> ((Trip) environment.getSource()).wheelchairAccessible)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("bikesAllowed")
                .type(new GraphQLNonNull(Scalars.GraphQLBoolean))
                .description("If it is allowed to take bicycle on this trip")
                .dataFetcher(environment -> ((Trip) environment.getSource()).bikesAllowed)
                .build())
            .build();

        tripPatternType = GraphQLObjectType.newObject()
            .name("TripPattern")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("tripPatternIdx")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Transport network unique integer ID of tripPattern")
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routeIdx")
                .type(Scalars.GraphQLInt)
                .description("Transport network unique integer ID of route")
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routeId")
                .type(Scalars.GraphQLString)
                .description("GTFS route ID (Agency unique)")
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("directionId")
                .type(Scalars.GraphQLString)
                .description("Direction ID of all trips in this trip pattern")
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stops")
                .type(new GraphQLList(stopType))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("trips")
                .type(new GraphQLList(tripType))
                .build())
            .build();

        streetEdgeInfoType = GraphQLObjectType.newObject()
            .name("StreetEdgeInfo")
            .description("This is a response model class which holds data that will be serialized and returned to the client. It is not used internally in routing. It represents a single street edge in a series of on-street (walking/biking/driving) directions. TODO could this be merged with WalkStep when profile routing and normal routing converge?")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("edgeId")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("OTP internal edge ID")
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).edgeId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("distance")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Distance of driving on these edge (mm)")
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).distance)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryPolyline")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> PolyUtil
                    .encode(((StreetEdgeInfo) environment.getSource()).geometry))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryWKT").type(Scalars.GraphQLString)
                .dataFetcher(
                    environment -> ((StreetEdgeInfo) environment.getSource()).geometry.toString())
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryGeoJSON")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> {
                    try {
                        return JsonUtilities.objectMapper.writeValueAsString(
                            ((StreetEdgeInfo) environment.getSource()).geometry);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("mode")
                .type(nonTransitModeEnum)
                .description("Which mode is used for driving (CAR, BICYCLE, WALK)")
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).mode)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("streetName")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).streetName)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("relativeDirection")
                .type(relativeDirectionEnum)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).relativeDirection)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("absoluteDirection")
                .type(absoluteDirectionEnum)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).absoluteDirection)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stayOn")
                .type(new GraphQLNonNull(Scalars.GraphQLBoolean))
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).stayOn)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("area")
                .type(Scalars.GraphQLBoolean)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).area)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("exit")
                .type(Scalars.GraphQLString)
                .description("Exit name when exiting highway or roundabout")
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).exit)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("bogusName")
                .type(Scalars.GraphQLBoolean)
                .description("True if name is generated (cycleway, footway, sidewalk, etc)")
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).bogusName)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("bikeRentalOnStation")
                .type(bikeRentalStationType)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).bikeRentalOnStation)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("bikeRentalOffStation")
                .type(bikeRentalStationType)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).bikeRentalOffStation)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("parkRide")
                .type(parkRideParkingType)
                .dataFetcher(environment -> ((StreetEdgeInfo) environment.getSource()).parkRide)
                .build())
            .build();

        elevationType = GraphQLObjectType.newObject()
            .name("Elevation")
            //.description("TODO: this could also be copressed like Mapquest is doing")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("distance")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Distance from start of segment in meters")
                .dataFetcher(environment -> ((Elevation) environment.getSource()).distance)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("elevation")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .description("Height in m at this distance")
                .dataFetcher(environment -> ((Elevation) environment.getSource()).elevation)
                .build())
            .build();

        alertType = GraphQLObjectType.newObject()
            .name("Alert")
            .description("Simple alert")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("alertHeaderText")
                .type(Scalars.GraphQLString)
                .description("Header of alert if it exists")
                .dataFetcher(environment -> ((Alert) environment.getSource()).alertHeaderText)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("alertDescriptionText")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Long description of alert notnull")
                .dataFetcher(environment -> ((Alert) environment.getSource()).alertDescriptionText)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("alertUrl")
                .type(Scalars.GraphQLString)
                .description("Url with more information")
                .dataFetcher(environment -> ((Alert) environment.getSource()).alertUrl)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("effectiveStartDate")
                .type(Scalars.GraphQLString)
                .description("When this alerts comes into effect")
                .dataFetcher(environment -> ((Alert) environment.getSource()).effectiveStartDate)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("effectiveEndDate")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> ((Alert) environment.getSource()).effectiveEndDate)
                .build())
            .build();

        streetSegmentType = GraphQLObjectType.newObject()
            .name("StreetSegment")
            .description("A response object describing a non-transit part of an Option. This is either an access/egress leg of a transit trip, or a direct path to the destination that does not use transit.")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("mode")
                .type(new GraphQLNonNull(legModeEnum))
                .description("Which mode of transport is used")
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).mode)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("duration")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Time in seconds for this part of trip")
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).duration)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("distance")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Distance in mm for this part of a trip")
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).distance)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryPolyline")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> PolyUtil
                    .encode(((StreetSegment) environment.getSource()).geometry))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryWKT").type(Scalars.GraphQLString)
                .dataFetcher(
                    environment -> ((StreetSegment) environment.getSource()).geometry.toString())
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("geometryGeoJSON")
                .type(Scalars.GraphQLString)
                .dataFetcher(environment -> {
                    try {
                        return JsonUtilities.objectMapper.writeValueAsString(
                            ((StreetSegment) environment.getSource()).geometry);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("streetEdges")
                .type(new GraphQLList(streetEdgeInfoType))
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).streetEdges)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("elevation")
                .type(new GraphQLList(elevationType))
                .description("List of elevation elements each elevation has a distance (from start of this segment) and elevation at this point (in meters)")
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).elevation)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("alerts")
                .type(new GraphQLList(alertType))
                .dataFetcher(environment -> ((StreetSegment) environment.getSource()).alerts)
                .build())
            .build();

        segmentPatternType = GraphQLObjectType.newObject()
            .name("SegmentPattern")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("patternId")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Trip Pattern id (Currently pattern index)")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).patternId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("patternIdx")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Transport network unique integer ID of tripPattern")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).patternIdx)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routeIdx")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Transport network unique integer ID of route")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).routeIndex)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("fromIndex")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of stop in trip pattern where trip was started")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).fromIndex)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("toIndex")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of stop in trip pattern where trip was stopped")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).toIndex)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("nTrips")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Number of trips (on this pattern??)")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).nTrips)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("realTime")
                .type(Scalars.GraphQLBoolean)
                .description("Do we have realTime data for arrival/deparure times")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).realTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("fromArrivalTime")
                .type(new GraphQLList(GraphQLZonedDateTime))
                .description("arrival times of from stop in this pattern")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).fromArrivalTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("fromDepartureTime")
                .type(new GraphQLList(GraphQLZonedDateTime))
                .description("departure times of from stop in this pattern")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).fromDepartureTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("toArrivalTime")
                .type(new GraphQLList(GraphQLZonedDateTime))
                .description("arrival times of to stop in this pattern")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).toArrivalTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("toDepartureTime")
                .type(new GraphQLList(GraphQLZonedDateTime))
                .description("departure times of to stop in this pattern")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).toDepartureTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("tripId")
                .type(new GraphQLList(Scalars.GraphQLString))
                .description("Trip ID of trip with same index as from/to arrival/departure times")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).tripIds)
                .build())
            .build();

        transitSegmentType = GraphQLObjectType.newObject()
            .name("TransitSegment")
            .description("The equivalent of a ride in an API response. Information degenerates to Strings and ints here.")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("from")
                .type(stopType)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).from)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("to")
                .type(stopType)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).to)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("waitStats")
                .type(statsType)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).waitStats)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("mode")
                .type(transitmodeEnum)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).mode)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("rideStats")
                .type(statsType)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).rideStats)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("routes")
                .type(new GraphQLList(routeType))
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).getRoutes().stream().collect(
                    Collectors.toList()))
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("segmentPatterns")
                .type(new GraphQLList(segmentPatternType))
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).segmentPatterns)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("middle")
                .type(streetSegmentType)
                .description("Part of a journey on the street between transit stops (transfers)")
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).middle)
                .build())
            .build();

        transitJourneyIDType = GraphQLObjectType.newObject()
            .name("TransitJourneyID")
            .description("Tells which pattern and time in pattern to use for this specific transit")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("pattern")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of segment pattern")
                .dataFetcher(environment -> ((TransitJourneyID) environment.getSource()).pattern)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("time")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of time in chosen pattern")
                .dataFetcher(environment -> ((TransitJourneyID) environment.getSource()).time)
                .build())
            .build();

        pointToPointConnectionType = GraphQLObjectType.newObject()
            .name("PointToPointConnection")
            .description("Object which pulls together specific access, transit and egress part of an option")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("access")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of access part of this trip")
                .dataFetcher(environment -> ((PointToPointConnection) environment.getSource()).access)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("egress")
                .type(Scalars.GraphQLInt)
                .description("Index of egress part of this trip")
                .dataFetcher(environment -> ((PointToPointConnection) environment.getSource()).egress)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transit")
                .type(new GraphQLList(transitJourneyIDType))
                .description("chooses which specific trip should be used Index in transit list specifies transit with same index Each TransitJourneyID has pattern in chosen index an time index in chosen pattern This can uniquly identify specific trip with transit")
                .dataFetcher(environment -> ((PointToPointConnection) environment.getSource()).transit)
                .build())
            .build();

        itineraryType = GraphQLObjectType.newObject()
            .name("Itinerary")
            .description("Object represents specific trip at a specific point in time with specific access, transit and egress parts")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("waitingTime")
                .type(Scalars.GraphQLInt)
                .description("Waiting time between transfers in seconds")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).waitingTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("walkTime")
                .type(Scalars.GraphQLInt)
                .description("Time when walking in seconds")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).walkTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("distance")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Distance in mm of all non-transit parts of this itinerary")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).distance)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transfers")
                .type(Scalars.GraphQLInt)
                .description("Number of transfers between different transit vehicles")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).transfers)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("duration")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("How much time did whole trip took in seconds")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).duration)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transitTime")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("How much time did we spend on transit in seconds")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).transitTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("connection")
                .type(pointToPointConnectionType)
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).connection)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("startTime")
                .type(new GraphQLNonNull(GraphQLZonedDateTime))
                .description("ISO 8061 date time when this journey started")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).startTime)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("endTime")
                .type(new GraphQLNonNull(GraphQLZonedDateTime))
                .description("ISO 8061 date time when this journey was over")
                .dataFetcher(environment -> ((Itinerary) environment.getSource()).endTime)
                .build())
            .build();

        profileOptionType = GraphQLObjectType.newObject()
            .name("ProfileOption")
            .description("This is a response model class which holds data that will be serialized and returned to the client. It is not used internally in routing.")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transit")
                .type(new GraphQLList(transitSegmentType))
                .description("Transit leg of a journey")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).transit)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("access")
                .type(new GraphQLNonNull(new GraphQLList(streetSegmentType)))
                .description("Part of journey from start to transit (or end)")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).access)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("egress")
                .type(new GraphQLList(streetSegmentType))
                .description("Part of journey from transit to end")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).egress)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("itinerary")
                .type(new GraphQLList(itineraryType))
                .description("Connects all the trip part to a trip at specific time with specific modes of transportation")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).itinerary)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stats")
                .type(new GraphQLNonNull(statsType))
                .description("Time stats for this part of a journey")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).stats)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("summary")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Text description of this part of a journey")
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).summary)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("fares")
                .type(new GraphQLList(fareType))
                .build())
            .build();

        profileResponseType = GraphQLObjectType.newObject()
            .name("ProfileResponse")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("options")
                .type(new GraphQLList(profileOptionType))

                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("patterns")
                .type(new GraphQLList(tripPatternType))
                .build())
            .build();

        GraphQLFieldDefinition profileField = GraphQLFieldDefinition.newFieldDefinition()
            .name("profile")
            .description("Gets profile of all paths")
            .type(profileResponseType)
            .argument(GraphQLArgument.newArgument()
                .name("fromLat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("fromLon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toLat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toLon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("fromTime")
                .type(GraphQLZonedDateTime)
                .description("The beginning of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM")
                //.defaultValue("7:30")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toTime")
                .type(GraphQLZonedDateTime)
                .description("The end of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM")
                //.defaultValue("10:30")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("wheelchair")
                .type(Scalars.GraphQLBoolean)
                .defaultValue(false)
                .description("Search path for wheelchair users")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("walkSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of walking, in meters per second")
                .defaultValue(1.3)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of cycling, in meters per second")
                .defaultValue(4.0)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("carSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of driving, in meters per second")
                .defaultValue(20.0)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("streetTime")
                .type(Scalars.GraphQLInt)
                .description(
                    "Maximum time in minutes to reach the destination without using transit")
                .defaultValue(60)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxWalkTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum walk time before and after using transit, in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum bike time when using transit in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxCarTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum car time before when using transit in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to ride a bike (to prevent extremely short bike legs)")
                .defaultValue(5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minCarTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to drive (to prevent extremely short driving legs)")
                .defaultValue(5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(Scalars.GraphQLInt)
                .description("the maximum number of options presented PER ACCESS MODE")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("accessModes")
                .type(new GraphQLList(accessLegModeEnum))
                .description("The modes used to reach transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("egressModes")
                .type(new GraphQLList(otherLegModeEnum))
                .description("The modes used to reach the destination after leaving transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("directModes")
                .type(new GraphQLList(otherLegModeEnum))
                .description("The modes used to reach the destination without transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("transitModes")
                .type(new GraphQLList(transitmodeEnum))
                .description("The transit modes used")
                .build())
            /*.argument(GraphQLArgument.newArgument()
                .name("analyst")
                .type(Scalars.GraphQLBoolean)
                .description(
                    "If true, disable all goal direction and propagate results to the street network")
                .defaultValue(false)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("reachabilityThreshold")
                .type(Scalars.GraphQLFloat)
                .description(
                    "The minimum proportion of the time for which a destination must be accessible for it to be included in the average")
                .defaultValue(0.5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSafe")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of maximizing safety when cycling")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSlope")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of minimizing hills when cycling")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeTime")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of minimizing time when cycling")
                .build())*/
            .argument(GraphQLArgument.newArgument()
                .name("suboptimalMinutes")
                .type(Scalars.GraphQLInt)
                .defaultValue(5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeTrafficStress")
                .type(Scalars.GraphQLInt)
                .description("maximum level of traffic stress for cycling, 1 - 4 (default 4)")
                .defaultValue(4)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(Scalars.GraphQLInt)
                .description("the maximum number of options presented PER ACCESS MODE")
                .defaultValue(15)
                .build())
            .dataFetcher(environment -> {
                return profileResponse.getPlan(ProfileRequest.fromEnvironment(environment, profileResponse.getTimezone()));
            })
            .build();

        GraphQLFieldDefinition planField = GraphQLFieldDefinition.newFieldDefinition()
            .name("plan")
            .description("Gets plan of a route at a specific time")
            .type(profileResponseType)
            .argument(GraphQLArgument.newArgument()
                .name("fromLat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("fromLon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toLat")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toLon")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("fromTime")
                .type(GraphQLZonedDateTime)
                .description("The beginning of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM")
                //.defaultValue("7:30")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toTime")
                .type(GraphQLZonedDateTime)
                .description("The end of the departure window, in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM")
                //.defaultValue("10:30")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("wheelchair")
                .type(Scalars.GraphQLBoolean)
                .defaultValue(false)
                .description("Search path for wheelchair users")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("searchType")
                .type(searchTypeEnum)
                .defaultValue(SearchType.DEPART_FROM)
                .description("Type of search")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("walkSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of walking, in meters per second")
                .defaultValue(1.3)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of cycling, in meters per second")
                .defaultValue(4.0)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("carSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of driving, in meters per second")
                .defaultValue(20.0)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("streetTime")
                .type(Scalars.GraphQLInt)
                .description(
                    "Maximum time in minutes to reach the destination without using transit")
                .defaultValue(60)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxWalkTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum walk time before and after using transit, in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum bike time when using transit in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxCarTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum car time before when using transit in minutes")
                .defaultValue(30)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to ride a bike (to prevent extremely short bike legs)")
                .defaultValue(5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minCarTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to drive (to prevent extremely short driving legs)")
                .defaultValue(5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(Scalars.GraphQLInt)
                .description("the maximum number of options presented PER ACCESS MODE")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("accessModes")
                .type(new GraphQLNonNull(new GraphQLList(accessLegModeEnum)))
                .defaultValue(EnumSet.of(LegMode.WALK, LegMode.BICYCLE))
                .description("The modes used to reach transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("egressModes")
                .type(new GraphQLNonNull(new GraphQLList(otherLegModeEnum)))
                .defaultValue(EnumSet.of(LegMode.WALK))
                .description("The modes used to reach the destination after leaving transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("directModes")
                .type(new GraphQLList(otherLegModeEnum))
                .defaultValue(EnumSet.of(LegMode.WALK, LegMode.BICYCLE))
                .description("The modes used to reach the destination without transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("transitModes")
                .type(new GraphQLList(transitmodeEnum))
                .defaultValue(EnumSet.of(TransitModes.TRANSIT))
                .description("The transit modes used")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeTrafficStress")
                .type(Scalars.GraphQLInt)
                .description("maximum level of traffic stress for cycling, 1 - 4 (default 4)")
                .defaultValue(4)
                .build())
            /*.argument(GraphQLArgument.newArgument()
                .name("analyst")
                .type(Scalars.GraphQLBoolean)
                .description(
                    "If true, disable all goal direction and propagate results to the street network")
                .defaultValue(false)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("reachabilityThreshold")
                .type(Scalars.GraphQLFloat)
                .description(
                    "The minimum proportion of the time for which a destination must be accessible for it to be included in the average")
                .defaultValue(0.5)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSafe")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of maximizing safety when cycling")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSlope")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of minimizing hills when cycling")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeTime")
                .type(Scalars.GraphQLInt)
                .description("The relative importance of minimizing time when cycling")
                .build())*/
            .argument(GraphQLArgument.newArgument()
                .name("suboptimalMinutes")
                .type(Scalars.GraphQLInt)
                .defaultValue(5)
                .build())
            .dataFetcher(environment -> {
                 return profileResponse.getPlan(ProfileRequest.fromEnvironment(environment, profileResponse.getTimezone()));
            })
            .build();


        this.queryType = GraphQLObjectType.newObject()
            .name("QueryType")
            .field(profileField)
            .field(planField)
            .build();

        indexSchema = graphql.schema.GraphQLSchema.newSchema()
            .query(queryType)
            .build();

        // @formatter:on
    }
}
