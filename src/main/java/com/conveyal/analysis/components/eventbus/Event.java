package com.conveyal.analysis.components.eventbus;

import com.conveyal.analysis.UserPermissions;

import java.util.Date;
import java.util.Set;

/**
 * This could extend BaseModel, but it's not a domain model class (describing transit or land use data or analysis),
 * it's metadata about server operation and user activity.
 */
public abstract class Event {

    /**
     * The time at which this event happened. Note that this timestamp is distinct from (and more accurate than) the
     * Mongo object ID creation time, as insertion into the database is deferred and Mongo ID timestamps have only
     * one-second resolution. We could set the timestamp inside the synchronized block that adds the event to the
     * FlightRecorder, in an effort to impose strict temporal ordering on threads coherent with the timestamp order.
     * But the timestamp has only millisecond resolution so that might not be very effective.
     */
    public Date timestamp =  new Date();
    public String user;
    public Set<String> groups;
    public boolean success = true;

    // Location fields for user city / lat / lon derived from IP address? Embed those in the UserPermissions?
    // Could also include lat / lon for affected entities, to allow easy map visualization.

    public Event forUser (UserPermissions userPermissions) {
        this.user = userPermissions.email;
        this.groups = userPermissions.groups;
        return this;
    }

    public Event forUser (String user, String... groups) {
        this.user = user;
        this.groups = Set.of(groups);
        return this;
    }

    /**
     * Serialize the specific subtype of event to facilitate filtering.
     * Not using JsonSubtypes annotations because we do not currently anticipate deserializing these objects.
     * Though I suppose it could be interesting to analyze historical events in Java code.
     */
    public String getType () {
        // Will resolve to specific subclass
        return this.getClass().getSimpleName();
    }

}
