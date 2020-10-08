package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.model.Entity;

/**
 * This is an interface for classes that can iterate over all entities in a single GTFS table, or fetch single entities
 * by ID, or fetch ordered groups of entities with the same ID (e.g. all stop times with the same trip_id).
 * Created by abyrd on 2017-04-06
 */
public interface TableReader <T extends Entity> extends Iterable<T> {

    public T get (String id);

    // public Iterable<T> getAll ();

    // public Iterable<T> getAllOrdered ();

    public Iterable<T> getOrdered (String id);

    public void close ();

}
