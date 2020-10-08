package com.conveyal.analysis.components;

import com.conveyal.analysis.UserPermissions;
import spark.Request;

/**
 * An interface for determining who is issuing an HTTP request, and what permissions they have to act upon data in the
 * analysis system. Currently all permissions information is known from the group the user belongs to: a user may take
 * any action on objects belonging to their group.
 */
public interface Authentication extends Component {
    /**
     * Given an incoming HTTP request, determine who the user is and create a UserPermissions object with that
     * information. If the user cannot be identified and/or authenticated, don't set those attributes and throw an
     * exception. Otherwise return a UserPermissions object.
     */
    UserPermissions authenticate (Request request);
}