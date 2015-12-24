package com.conveyal.r5;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.*;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.model.json_serialization.PolyUtil;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.GraphQLException;
import graphql.Scalars;
import graphql.language.StringValue;
import graphql.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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

    public static GraphQLEnumType legModeEnum = GraphQLEnumType.newEnum()
        .name("LegMode")
        .description("Modes of transport on ingress egress legs")
        .value("WALK", LegMode.WALK)
        .value("BICYCLE", LegMode.BICYCLE)
        .value("CAR", LegMode.CAR)
        .value("BICYCLE_RENT", LegMode.BICYCLE_RENT, "Renting a bicycle")
        .value("CAR_PARK", LegMode.CAR_PARK, "Park & Ride")
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

    public static GraphQLScalarType GraphQLLocalDate = new GraphQLScalarType("LocalDate",
        "Java 8 LocalDate type YYYY-MM-DD", new Coercing() {
        @Override
        public Object coerce(Object input) {
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
        public Object coerceLiteral(Object input) {
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

    private final String INPUTCOORDINATENAME = "Coordinate";

    // @formatter:off
    public GraphQLInputObjectType inputCoordinateType = GraphQLInputObjectType.newInputObject()
            .name(INPUTCOORDINATENAME)
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lat")
                .type(Scalars.GraphQLFloat)
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lon")
                .type(Scalars.GraphQLFloat)
                .build())
            .build();


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
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("type")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .dataFetcher(environment -> ((Fare) environment.getSource()).type) //this is not necessary
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("low")
                .type(new GraphQLNonNull(Scalars.GraphQLFloat))
                .dataFetcher(environment -> ((Fare) environment.getSource()).low) //this is not necessary
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("transferReduction")
                .type(Scalars.GraphQLBoolean) //transferReduction is read because isTransferReduction function exists
                .build())
            .build();

        stopType = GraphQLObjectType.newObject()
            .name("Stop")
            .description("Transit stop")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("stopId")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Stop ID")
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
                .type(wheelchairBoardingEnum)
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

        statsType = GraphQLObjectType.newObject()
            .name("Stats")
            .field(GraphQLFieldDefinition.newFieldDefinition().name("min")
                .type(new GraphQLNonNull(Scalars.GraphQLInt)).description("Minimal time")
                .dataFetcher(environment -> ((Stats) environment.getSource()).min).build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("avg")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Average time")
                .dataFetcher(environment -> ((Stats) environment.getSource()).avg)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("max")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Maximal time")
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
                .name("shortName")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
                .description("Short name of the route. Usually number or number plus letter")
                .dataFetcher(environment -> ((Route) environment.getSource()).shortName)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("longName")
                .type(new GraphQLNonNull(Scalars.GraphQLString))
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
                .description("Distance of driving on these edge (meters)")
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
                .type(Scalars.GraphQLBoolean)
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
                .description("Trip Pattern id")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).patternId)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("fromIndex")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of stop where trip was started")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).fromIndex)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("toIndex")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Index of stop where trip was stopped")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).toIndex)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("nTrips")
                .type(new GraphQLNonNull(Scalars.GraphQLInt))
                .description("Number of trips (on this pattern??)")
                .dataFetcher(environment -> ((SegmentPattern) environment.getSource()).nTrips)
                .build())
            .build();

        transitSegmentType = GraphQLObjectType.newObject()
            .name("TransitSegment")
            .description("The equivalent of a ride in an API response. Information degenerates to Strings and ints here.")
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("from")
                .type(stopClusterType)
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).from)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("to")
                .type(stopClusterType)
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
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).routes)
                .build())
            .field(GraphQLFieldDefinition.newFieldDefinition()
                .name("segmentPatterns")
                .type(new GraphQLList(segmentPatternType))
                .dataFetcher(environment -> ((TransitSegment) environment.getSource()).segmentPatterns)
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
                .dataFetcher(environment -> ((ProfileOption) environment.getSource()).fares)
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
                .type(new GraphQLList(segmentPatternType))
                .build())
            .build();

        GraphQLFieldDefinition profileField = GraphQLFieldDefinition.newFieldDefinition()
            .name("profile")
            .description("Gets profile of all paths")
            .type(profileResponseType)
            .argument(GraphQLArgument.newArgument()
                .name("from")
                .type(new GraphQLNonNull(inputCoordinateType))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("to")
                .type(new GraphQLNonNull(inputCoordinateType))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("fromTime")
                .type(Scalars.GraphQLInt)
                .description("The beginning of the departure window, in seconds since midnight.")
                .defaultValue(27000) //7:30
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("toTime")
                .type(Scalars.GraphQLInt)
                .description("The end of the departure window, in seconds since midnight.")
                .defaultValue(34200) //8:30
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
                .defaultValue(1.4)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of cycling, in meters per second")
                .defaultValue(4.1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("carSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of driving, in meters per second")
                .defaultValue(20)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("streetTime")
                .type(Scalars.GraphQLInt)
                .description(
                    "Maximum time in minutes to reach the destination without using transit")
                .defaultValue(90)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxWalkTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum walk time before and after using transit, in minutes")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum bike time when using transit in minutes")
                .defaultValue(20)
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
                .defaultValue(1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minCarTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to drive (to prevent extremely short driving legs)")
                .defaultValue(1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("date")
                .type(GraphQLLocalDate)
                .description("The date of the search YYYY-MM-DD")
                .defaultValue("today")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(Scalars.GraphQLInt)
                .description("the maximum number of options presented PER ACCESS MODE")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("accessModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("egressModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach the destination after leaving transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("directModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach the destination without transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("transitModes")
                .type(new GraphQLList(transitmodeEnum))
                .description("The transit modes used")
                .build())
            .argument(GraphQLArgument.newArgument()
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
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("suboptimalMinutes")
                .type(Scalars.GraphQLInt)
                .build())
            .dataFetcher(environment -> {
                return profileResponse;
            })
            .build();

        GraphQLFieldDefinition planField = GraphQLFieldDefinition.newFieldDefinition()
            .name("plan")
            .description("Gets plan of a route at a specific time")
            .type(profileResponseType)
            .argument(GraphQLArgument.newArgument().name("from")
                .type(new GraphQLNonNull(inputCoordinateType)).build())
            .argument(GraphQLArgument.newArgument()
                .name("to")
                .type(new GraphQLNonNull(inputCoordinateType))
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("time")
                .type(Scalars.GraphQLInt)
                .description("The beginning of the departure window, in seconds since midnight.")
                .defaultValue(27000) //7:30
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
                .defaultValue(1.4)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("bikeSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of cycling, in meters per second")
                .defaultValue(4.1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("carSpeed")
                .type(Scalars.GraphQLFloat)
                .description("The speed of driving, in meters per second")
                .defaultValue(20)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("streetTime")
                .type(Scalars.GraphQLInt)
                .description(
                    "Maximum time in minutes to reach the destination without using transit")
                .defaultValue(90)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxWalkTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum walk time before and after using transit, in minutes")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("maxBikeTime")
                .type(Scalars.GraphQLInt)
                .description("Maximum bike time when using transit in minutes")
                .defaultValue(20)
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
                .defaultValue(1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("minCarTime")
                .type(Scalars.GraphQLInt)
                .description("Minimum time to drive (to prevent extremely short driving legs)")
                .defaultValue(1)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("date")
                .type(GraphQLLocalDate)
                .description("The date of the search YYYY-MM-DD")
                .defaultValue("today")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(Scalars.GraphQLInt)
                .description("the maximum number of options presented PER ACCESS MODE")
                .defaultValue(15)
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("accessModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("egressModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach the destination after leaving transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("directModes")
                .type(new GraphQLList(legModeEnum))
                .description("The modes used to reach the destination without transit")
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("transitModes")
                .type(new GraphQLList(transitmodeEnum))
                .description("The transit modes used")
                .build())
            .argument(GraphQLArgument.newArgument()
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
                .build())
            .argument(GraphQLArgument.newArgument()
                .name("suboptimalMinutes")
                .type(Scalars.GraphQLInt)
                .build())
            .dataFetcher(environment -> {
                 return profileResponse.getPlan(ProfileRequest.fromEnvironment(environment));
            })
            .build();


        GraphQLFieldDefinition fareField = GraphQLFieldDefinition.newFieldDefinition()
            .name("fare")
            .description("Gets information about fare")
            .type(fareType)
            .dataFetcher(environment -> Fare.SampleFare())
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
