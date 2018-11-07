package com.conveyal.r5.speed_test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

enum SpeedTestProfiles {
    original("original", "The original code with FastRaptorWorker"),
    int_arrays("int", "Flyweight stop state using int arrays with new RangeRaptorWorker"),
    struct_arrays("struct", "Simple POJO stop arrival state with new RangeRaptorWorker"),
    multi_criteria("mc", "Multi criteria pareto state with new McRangeRaptor");

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
        return is(original);
    }

    public boolean isMultiCriteria() {
        return is(multi_criteria);
    }

    /* private methods */

    private boolean is(SpeedTestProfiles other) {
        return this == other;
    }

    private boolean isStructArrays() {
        return is(struct_arrays);
    }

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