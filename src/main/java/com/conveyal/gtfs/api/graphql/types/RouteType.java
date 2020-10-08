package com.conveyal.gtfs.api.graphql.types;

import com.conveyal.gtfs.api.graphql.fetchers.PatternFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.TripDataFetcher;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;

import static com.conveyal.gtfs.api.util.GraphQLUtil.doublee;
import static com.conveyal.gtfs.api.util.GraphQLUtil.feed;
import static com.conveyal.gtfs.api.util.GraphQLUtil.intt;
import static com.conveyal.gtfs.api.util.GraphQLUtil.longArg;
import static com.conveyal.gtfs.api.util.GraphQLUtil.multiStringArg;
import static com.conveyal.gtfs.api.util.GraphQLUtil.string;
import static graphql.Scalars.GraphQLLong;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Created by landon on 10/3/16.
 */
public class RouteType {
    public static GraphQLObjectType build () {
        // routeStats should be modeled after com.conveyal.gtfs.stats.model.RouteStatistic
        GraphQLObjectType routeStats = newObject()
                .name("routeStats")
                .description("Statistics about a route")
                .field(doublee("headway"))
                .field(doublee("avgSpeed"))
                .field(doublee("stopSpacing"))
                .build();

        return newObject()
                .name("route")
                .description("A GTFS route object")
                .field(string("route_id"))
                // TODO agency
                .field(string("route_short_name"))
                .field(string("route_long_name"))
                .field(string("route_desc"))
                .field(string("route_url"))
                // TODO route_type as enum
                .field(intt("route_type"))
                .field(string("route_color"))
                .field(string("route_text_color"))
                .field(feed())
                .field(newFieldDefinition()
                        .type(new GraphQLList(new GraphQLTypeReference("trip")))
                        .name("trips")
                        .dataFetcher(TripDataFetcher::fromRoute)
                        .build()
                )
                .field(newFieldDefinition()
                        .type(GraphQLLong)
                        .name("trip_count")
                        .dataFetcher(TripDataFetcher::fromRouteCount)
                        .build()
                )
                .field(newFieldDefinition()
                        .type(new GraphQLList(new GraphQLTypeReference("pattern")))
                        .name("patterns")
                        .argument(multiStringArg("stop_id"))
                        .argument(multiStringArg("pattern_id"))
                        .argument(longArg("limit"))
                        .dataFetcher(PatternFetcher::fromRoute)
                        .build()
                )
                .field(newFieldDefinition()
                        .type(GraphQLLong)
                        .name("pattern_count")
                        .dataFetcher(PatternFetcher::fromRouteCount)
                        .build()
                )
                .build();
    }}
