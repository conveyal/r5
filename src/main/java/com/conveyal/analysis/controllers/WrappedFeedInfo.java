package com.conveyal.analysis.controllers;

import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.FeedInfo;

/**
 * Wrap feed info with GTFS feed checksum and feed unique ID.
 */
public class WrappedFeedInfo extends WrappedGTFSEntity<FeedInfo> {
    public long checksum;


    /**
     * Wrap the given GTFS entity with the unique Feed ID specified (this is not generally a GTFS feed ID as they
     * are not unique between different versions of the same feed. Also pass in feed checksum.
     */
    public WrappedFeedInfo(String feedUniqueID, FeedInfo entity, long checksum) {
        super(feedUniqueID, entity);
        this.checksum = checksum;
    }
}
