package com.conveyal.r5.api.util;

import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransitLayer;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transit stop
 */
public class Stop {
    //GTFS Stop ID @notnull
    @JsonProperty("id")
    public String stopId;
    //Stop name @notnull
    public String name;
    //Coordinate @notnull
    public float lat, lon;
    //Short text or number that identifies this stop to passengers
    public String code;
    //Fare zone for stop
    public String zoneId;
    public Boolean wheelchairBoarding;

    /**
     * Sets stopId, stop name and latitude, longitude and wheelchairBoarding from transitLayer
     *
     * @param stopIdx index of stop in transitLayer
     * @param transitLayer Transit Layer
     */
    public Stop(int stopIdx, TransitLayer transitLayer) {
        stopId = transitLayer.stopIdForIndex.get(stopIdx);
        name = transitLayer.stopNames.get(stopIdx);
        VertexStore.Vertex vertex = transitLayer.parentNetwork.streetLayer.vertexStore.getCursor();
        vertex.seek(transitLayer.streetVertexForStop.get(stopIdx));
        lat = (float) vertex.getLat();
        lon = (float) vertex.getLon();
        wheelchairBoarding = transitLayer.stopsWheelchair.get(stopIdx);
    }
}
