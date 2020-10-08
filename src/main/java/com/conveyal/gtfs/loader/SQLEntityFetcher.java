package com.conveyal.gtfs.loader;

import java.sql.PreparedStatement;

/**
 * Created by abyrd on 2017-04-04
 */
public abstract class SQLEntityFetcher<T> implements Iterable<T> {

    EntityPopulator<T> entityPopulator;

    public SQLEntityFetcher (PreparedStatement fetchAllStatement, EntityPopulator<T> entityPopulator) {
        this.entityPopulator = entityPopulator;
    }


}
