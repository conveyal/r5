package com.conveyal.gtfs.api.graphql;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

import java.lang.reflect.Field;

/**
 * Fetch data from wrapped GTFS entities. Modeled after graphql-java FieldDataFetcher.
 */
public class WrappedEntityFieldFetcher implements DataFetcher {
    private final String field;

    public WrappedEntityFieldFetcher (String field) {
        this.field = field;
    }

    @Override
    public Object get(DataFetchingEnvironment dataFetchingEnvironment) {
        Object source = dataFetchingEnvironment.getSource();

        if (source instanceof WrappedGTFSEntity) source = ((WrappedGTFSEntity) source).entity;

        Field field = null;
        try {
            field = source.getClass().getField(this.field);
        } catch (NoSuchFieldException e) {
            return null;
        }

        try {
            return field.get(source);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
