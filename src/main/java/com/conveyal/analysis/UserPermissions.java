package com.conveyal.analysis;

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

    @Override
    public String toString () {
        return "UserPermissions{" +
                "email='" + email + '\'' +
                ", accessGroup='" + accessGroup + '\'' +
                '}';
    }
}
