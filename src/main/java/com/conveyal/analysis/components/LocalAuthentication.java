package com.conveyal.analysis.components;

import com.conveyal.analysis.UserPermissions;
import spark.Request;

/**
 * Working locally (not running in a cloud hosting environment), hard-wire the user and group names.
 */
public class LocalAuthentication implements Authentication {

    public static final String LOCAL_USERNAME = "local";

    public static final String LOCAL_GROUP = LOCAL_USERNAME;

    @Override
    public UserPermissions authenticate (Request request) {
        return new UserPermissions(LOCAL_USERNAME, true, LOCAL_GROUP);
    }

}
