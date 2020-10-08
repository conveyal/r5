package com.conveyal.gtfs.api.graphql.types;

import com.conveyal.gtfs.api.graphql.WrappedEntityFieldFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.RouteFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.StopFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.TripDataFetcher;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;

import static com.conveyal.gtfs.api.util.GraphQLUtil.doublee;
import static com.conveyal.gtfs.api.util.GraphQLUtil.feed;
import static com.conveyal.gtfs.api.util.GraphQLUtil.lineString;
import static com.conveyal.gtfs.api.util.GraphQLUtil.longArg;
import static com.conveyal.gtfs.api.util.GraphQLUtil.string;
import static graphql.Scalars.GraphQLLong;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Created by landon on 10/3/16.
 */
public class PatternType {
    public static GraphQLObjectType build () {
        GraphQLObjectType patternStats = newObject()
                .name("patternStats")
                .description("Statistics about a pattern")
                .field(doublee("headway"))
                .field(doublee("avgSpeed"))
                .field(doublee("stopSpacing"))
                .build();

        return newObject()
                .name("pattern")
                .description("A unique sequence of stops that a GTFS route visits")
                .field(string("pattern_id"))
                .field(string("name"))
                .field(string("route_id"))
                .field(feed())
                .field(newFieldDefinition()
                        .name("route")
                        .description("Route that pattern operates along")
                        .dataFetcher(RouteFetcher::fromPattern)
                        .type(new GraphQLTypeReference("route"))
                        .build()
                )
                .field(newFieldDefinition()
                        .name("stops")
                        .description("Stops that pattern serves")
                        .dataFetcher(StopFetcher::fromPattern)
                        .type(new GraphQLList(new GraphQLTypeReference("stop")))
                        .build()
                )
                .field(newFieldDefinition()
                        .type(GraphQLLong)
                        .description("Count of stops that pattern serves")
                        .name("stop_count")
                        .dataFetcher(StopFetcher::fromPatternCount)
                        .build()
                )
                .field(newFieldDefinition()
                        .type(lineString())
                        .description("Geometry that pattern operates along")
                        .dataFetcher(new WrappedEntityFieldFetcher("geometry"))
                        .name("geometry")
                        .build()
                )
                .field(newFieldDefinition()
                        .name("trips")
                        .description("Trips associated with pattern")
                        .type(new GraphQLList(new GraphQLTypeReference("trip")))
                        .dataFetcher(TripDataFetcher::fromPattern)
                        .argument(longArg("begin_time"))
                        .argument(longArg("end_time"))
                        .build()
                )
                .field(newFieldDefinition()
                        .type(GraphQLLong)
                        .description("Count of trips associated with pattern")
                        .name("trip_count")
                        .dataFetcher(TripDataFetcher::fromPatternCount)
                        .build()
                )
                .build();
    }
}
