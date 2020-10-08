package com.conveyal.gtfs.api.graphql;

/**
 * Wraps a GTFS entity, whose own ID may only be unique within the feed, decorating it with the unique ID of the feed
 * it came from.
 */
public class WrappedGTFSEntity<T> {
    public T entity;
    public String feedUniqueId;

    /**
     * Wrap the given GTFS entity with the unique Feed ID specified (this is not generally a GTFS feed ID as they
     * are not unique between different versions of the same feed.
     */
    public WrappedGTFSEntity (String feedUniqueID, T entity) {
        this.feedUniqueId = feedUniqueID;
        this.entity = entity;
    }
}
