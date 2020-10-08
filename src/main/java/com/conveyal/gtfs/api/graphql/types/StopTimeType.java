package com.conveyal.gtfs.api.graphql.types;

import com.conveyal.gtfs.api.graphql.fetchers.TripDataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;

import static com.conveyal.gtfs.api.util.GraphQLUtil.*;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Created by landon on 10/3/16.
 */
public class StopTimeType {
    public static GraphQLObjectType build () {
        return newObject()
                .name("stopTime")
                .field(intt("arrival_time"))
                .field(intt("departure_time"))
                .field(intt("stop_sequence"))
                .field(string("stop_id"))
                .field(string("stop_headsign"))
                .field(doublee("shape_dist_traveled"))
                .field(feed())
                .field(newFieldDefinition()
                        .name("trip")
                        .type(new GraphQLTypeReference("trip"))
                        .dataFetcher(TripDataFetcher::fromStopTime)
                        .argument(stringArg("date"))
                        .argument(longArg("from"))
                        .argument(longArg("to"))
                        .build()
                )
                .build();
    }
}
