package com.conveyal.analysis.components.eventbus;

import com.conveyal.analysis.UserPermissions;

import java.util.Date;
import java.util.Set;

/**
 * This could extend BaseModel, but it's not a domain model class (describing transit or land use data or analysis).
 * It's metadata about server operation and user activity. These are intended to be serialized into a database or log,
 * so the field visibility and types of every subclass should take that into consideration.
 */
public abstract class Event {

    /**
     * The time at which this event happened. Note that this timestamp is distinct from (and more accurate than) the
     * Mongo object ID creation time, as insertion into the database is deferred and Mongo ID timestamps have only
     * one-second resolution. We could set the timestamp inside the synchronized block that adds the event to the
     * FlightRecorder, in an effort to impose strict temporal ordering on threads coherent with the timestamp order.
     * But the timestamp has only millisecond resolution so that might not be very effective.
     */
    public Date timestamp = new Date();
    public String user;
    public String accessGroup;
    public boolean success = true;

    // Location fields for user city / lat / lon derived from IP address? Embed those in the UserPermissions?
    // Could also include lat / lon for affected entities, to allow easy map visualization.

    /**
     * Set the user and groups from the supplied userPermissions object (if any) and return the modified instance.
     * These fluent setter methods return this abstract supertype instead of the specific subtype, which can be a
     * little awkward. But the alternative of declaring Event <S extends Event> and casting is more ugly.
     * @param userPermissions if this is null, the call will have no effect.
     */
    public Event forUser (UserPermissions userPermissions) {
        if (userPermissions != null) {
            this.user = userPermissions.email;
            this.accessGroup = userPermissions.accessGroup;
        }
        return this;
    }

    public Event forUser (String user, String accessGroup) {
        this.user = user;
        this.accessGroup = accessGroup;
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
