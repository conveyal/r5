package com.conveyal.analysis.components;

import com.conveyal.analysis.UserPermissions;
import spark.Request;

/**
 * Working "offline", hard-wire the user and group names. We might want to use something like "_local_" or "NONE"
 * instead of "OFFLINE" for the group, since someone running locally doesn't necessarily feel that they are offline.
 */
public class LocalAuthentication implements Authentication {

    public static final String LOCAL_USERNAME = "local";

    public static final String LOCAL_GROUP = LOCAL_USERNAME;

    @Override
    public UserPermissions authenticate (Request request) {
        UserPermissions userPermissions = new UserPermissions(LOCAL_USERNAME, true, LOCAL_GROUP);
        request.attribute("permissions", userPermissions);
        request.attribute("email", LOCAL_USERNAME);
        request.attribute("accessGroup", LOCAL_GROUP);
        return userPermissions;
    }

}
