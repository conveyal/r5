package com.conveyal.gtfs.util.json;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonProperty;

// ignore all by default
@JsonFilter("bbox")
public abstract class Rectangle2DMixIn {
    // stored as lon, lat
    @JsonProperty("west") public abstract double getMinX();
    @JsonProperty("east") public abstract double getMaxX();
    @JsonProperty("north") public abstract double getMaxY();
    @JsonProperty("south") public abstract double getMinY();
}
