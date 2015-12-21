package com.conveyal.r5.point_to_point.builder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Speeds can be specified in different units
 */
public enum SpeedUnit {
    KMH("kmh"),
    MPH("mph"),
    KNOTS("knots");

    private String shortUnit;

    SpeedUnit(String shortUnit) {
        this.shortUnit = shortUnit;
    }

    @JsonValue
    @Override
    public String toString() {
        return this.shortUnit;
    }

    @JsonCreator
    public static SpeedUnit fromString(String unit) {
        if (unit != null) {
            unit = unit.toLowerCase();
            switch (unit) {
            case "km/h":
            case "kmh":
            case "kmph":
            case "kph":
                return KMH;
            case "mph":
                return MPH;
            case "knots":
                return KNOTS;
            default:
                throw new IllegalArgumentException("Unknown unit:" + unit
                    + " supported units are km/h|kmh|kmph|kph, mph and knots");
            }
        }
        return null;
    }
}
