package com.conveyal.analysis;

import spark.Request;

import static com.conveyal.analysis.components.HttpApi.USER_PERMISSIONS_ATTRIBUTE;

/**
 * Groups together all information about what a user is allowed to do.
 * Currently all such information is known from the group ID.
 * In the future we might have an EnumSet of additional flags, or a simple enum of "power levels".
 */
public class UserPermissions {

    public final String email;

    public final String accessGroup;

    public final boolean admin;

    public UserPermissions (String email, boolean admin, String accessGroup) {
        this.email = email;
        this.admin = admin;
        this.accessGroup = accessGroup;
    }

    /**
     * From an HTTP request object, extract a strongly typed UserPermissions object containing the user's email and
     * access group. This should be used almost everywhere instead of String email and accessGroup variables. Use this
     * method to encapsulate all calls to req.attribute(String) because those calls are not typesafe (they cast an Object
     * to whatever type seems appropriate in the context, or is supplied by the "req.<T>attribute(String)" syntax).
     */
    public static UserPermissions from (Request req) {
        return req.attribute(USER_PERMISSIONS_ATTRIBUTE);
    }

    @Override
    public String toString () {
        return "UserPermissions{" +
                "email='" + email + '\'' +
                ", accessGroup='" + accessGroup + '\'' +
                '}';
    }
}
