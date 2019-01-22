package com.conveyal.r5.speed_test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

enum SpeedTestProfiles {
    original("or", "The original R5 FastRaptorWorker by Conveyal"),
    range_raptor("rr", "Standard Range Raptor, super fast [ transfers, arrival time, travel time ]."),
    raptor_reverse("rv", "Reverse Raptor"),
    mc_range_raptor("mc", "Multi-Criteria pareto optimal Range Raptor [ transfers, arrival time, travel time, walking ].");

    final String shortName;
    final String description;

    SpeedTestProfiles(String shortName, String description) {
        this.shortName = shortName;
        this.description = description;
    }

    public static SpeedTestProfiles[] parse(String profiles) {
        return Arrays.stream(profiles.split(",")).map(SpeedTestProfiles::parseOne).toArray(SpeedTestProfiles[]::new);
    }

    public static List<String> options() {
        return Arrays.stream(values()).map(SpeedTestProfiles::description).collect(Collectors.toList());
    }

    public boolean isOriginal() {
        return this == original;
    }

    /* private methods */

    private static SpeedTestProfiles parseOne(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            for (SpeedTestProfiles it : values()) {
                if (it.shortName.toLowerCase().startsWith(value)) {
                    return it;
                }
            }
            throw e;
        }
    }

    private String description() {
        if (name().equals(shortName)) {
            return String.format("'%s' : %s", name(), description);
        } else {
            return String.format("'%s' or '%s' : %s", name(), shortName, description);
        }
    }
}