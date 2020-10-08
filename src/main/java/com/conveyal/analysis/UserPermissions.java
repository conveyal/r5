package com.conveyal.analysis;

import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Groups together all information about what a user is allowed to do.
 * Currently all such information is known from the group ID.
 * In the future we might have an EnumSet of additional flags, or a simple enum of "power levels".
 */
public class UserPermissions {

    public final String email;

    public final Set<String> groups;

    public final boolean admin;

    public UserPermissions (String email, boolean admin, String... groups) {
        this.email = email;
        this.admin = admin;
        this.groups = Sets.newHashSet(groups);
    }

    @Override
    public String toString () {
        return "UserPermissions{" +
                "email='" + email + '\'' +
                ", groups='" + groups + '\'' +
                '}';
    }
}
