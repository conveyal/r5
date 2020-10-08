package com.conveyal.gtfs.api.util;

import com.conveyal.gtfs.api.graphql.GeoJsonCoercing;
import com.conveyal.gtfs.api.graphql.WrappedEntityFieldFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.FeedFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLTypeReference;

import static graphql.Scalars.*;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;

/**
 * Created by landon on 10/3/16.
 */
public class GraphQLUtil {

    public static GraphQLScalarType lineString () {
        return new GraphQLScalarType("GeoJSON", "GeoJSON", new GeoJsonCoercing());
    }

    public static GraphQLFieldDefinition string (String name) {
        return newFieldDefinition()
                .name(name)
                .type(GraphQLString)
                .dataFetcher(new WrappedEntityFieldFetcher(name))
                .build();
    }

    public static GraphQLFieldDefinition intt (String name) {
        return newFieldDefinition()
                .name(name)
                .type(GraphQLInt)
                .dataFetcher(new WrappedEntityFieldFetcher(name))
                .build();
    }

    public static GraphQLFieldDefinition doublee (String name) {
        return newFieldDefinition()
                .name(name)
                .type(GraphQLFloat)
                .dataFetcher(new WrappedEntityFieldFetcher(name))
                .build();
    }

    public static GraphQLFieldDefinition feed () {
        return newFieldDefinition()
                .name("feed")
                .description("Containing feed")
                .dataFetcher(FeedFetcher::forWrappedGtfsEntity)
                .type(new GraphQLTypeReference("feed"))
                .build();
    }

    public static GraphQLArgument stringArg (String name) {
        return newArgument()
                .name(name)
                .type(GraphQLString)
                .build();
    }

    public static GraphQLArgument multiStringArg (String name) {
        return newArgument()
                .name(name)
                .type(new GraphQLList(GraphQLString))
                .build();
    }

    public static GraphQLArgument floatArg (String name) {
        return newArgument()
                .name(name)
                .type(GraphQLFloat)
                .build();
    }

    public static GraphQLArgument longArg (String name) {
        return newArgument()
                .name(name)
                .type(GraphQLLong)
                .build();
    }

    public static boolean argumentDefined(DataFetchingEnvironment env, String name) {
        return (env.containsArgument(name) && env.getArgument(name) != null);
    }
}
