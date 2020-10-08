package com.conveyal.gtfs.api.graphql.types;

import com.conveyal.gtfs.api.graphql.fetchers.RouteFetcher;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;

import static com.conveyal.gtfs.api.util.GraphQLUtil.doublee;
import static com.conveyal.gtfs.api.util.GraphQLUtil.feed;
import static com.conveyal.gtfs.api.util.GraphQLUtil.intt;
import static com.conveyal.gtfs.api.util.GraphQLUtil.multiStringArg;
import static com.conveyal.gtfs.api.util.GraphQLUtil.string;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Created by landon on 10/3/16.
 */
public class StopType {
    public static GraphQLObjectType build () {

        // transferPerformance should be modeled after com.conveyal.gtfs.stats.model.TransferPerformanceSummary
        GraphQLObjectType transferPerformance = newObject()
                .name("transferPerformance")
                .description("Transfer performance for a stop")
                .field(string("fromRoute"))
                .field(string("toRoute"))
                .field(intt("bestCase"))
                .field(intt("worstCase"))
                .field(intt("typicalCase"))
                .build();

        // stopStats should be modeled after com.conveyal.gtfs.stats.model.StopStatistic
        GraphQLObjectType stopStats = newObject()
                .name("stopStats")
                .description("Statistics about a stop")
                .field(doublee("headway"))
                .field(intt("tripCount"))
                .field(intt("routeCount"))
                .build();

        return newObject()
                .name("stop")
                .description("A GTFS stop object")
                .field(string("stop_id"))
                .field(string("stop_name"))
                .field(string("stop_code"))
                .field(string("stop_desc"))
                .field(doublee("stop_lon"))
                .field(doublee("stop_lat"))
                .field(string("zone_id"))
                .field(string("stop_url"))
                .field(string("stop_timezone"))
                .field(feed())
                .field(newFieldDefinition()
                        .name("routes")
                        .description("The list of routes that serve a stop")
                        .type(new GraphQLList(new GraphQLTypeReference("route")))
                        .argument(multiStringArg("route_id"))
                        .dataFetcher(RouteFetcher::fromStop)
                        .build()
                )
                .build();
    }
}
