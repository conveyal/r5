package com.conveyal.r5.api;

import com.conveyal.r5.api.util.ProfileOption;

import java.util.List;
import java.util.Set;

/**
 * Created by mabu on 30.10.2015.
 */
public class ProfileResponse {
    public List<ProfileOption> options;

    @Override public String toString() {
        return "ProfileResponse{" +
            "options=" + options +
            '}';
    }

    public List<ProfileOption> getOptions() {
        return options;
    }
}
