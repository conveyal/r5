package com.conveyal.r5.transit;

/**
 * Thrown when a TransportNetwork is build with two feeds with the same feed ID.
 */
public class DuplicateFeedException extends RuntimeException {
    private String feedId;

    public DuplicateFeedException(String feedId) {
        this.feedId = feedId;
    }

    @Override
    public String getMessage () {
        return String.format("Bundle contains duplicate feeds with feed ID %s", feedId);
    }
}
