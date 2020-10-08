package com.conveyal.r5.api.util;

import com.conveyal.r5.point_to_point.PointToPointRouterServer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
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
    //Transit mode of route on first pattern that uses this stop
    public TransitModes mode;

    /**
     * Sets stopId, stop name and latitude, longitude and wheelchairBoarding from transitLayer
     *
     * @param stopIdx index of stop in transitLayer
     * @param transitLayer Transit Layer
     */
    public Stop(int stopIdx, TransitLayer transitLayer) {
        this(stopIdx, transitLayer, false, false);
    }

    /**
     * Sets stopId, stop name and latitude, longitude and wheelchairBoarding from transitLayer
     * @param stopIdx index of stop in transitLayer
     * @param transitLayer Transit Layer
     * @param fillMode if true TransitMode is filled
     * @param jitterCoordinates if true stop coordinates are jittered with {@link PointToPointRouterServer#jitter(VertexStore.Vertex)}
     */
    public Stop(int stopIdx, TransitLayer transitLayer, boolean fillMode, boolean jitterCoordinates) {
        stopId = transitLayer.stopIdForIndex.get(stopIdx);
        name = transitLayer.stopNames.get(stopIdx);
        VertexStore.Vertex vertex = transitLayer.parentNetwork.streetLayer.vertexStore.getCursor();
        vertex.seek(transitLayer.streetVertexForStop.get(stopIdx));
        if (jitterCoordinates) {
            org.locationtech.jts.geom.Coordinate jitteredCoordinates = PointToPointRouterServer.jitter(vertex);
            lat = (float) jitteredCoordinates.y;
            lon = (float) jitteredCoordinates.x;
        } else if (vertex.index > -1) {
            // TODO: proper way to handle this case is to use original stop lat/lon from the GTFS
            lat = (float) vertex.getLat();
            lon = (float) vertex.getLon();
        }
        wheelchairBoarding = transitLayer.stopsWheelchair.get(stopIdx);

        if (fillMode) {
            final int[] patternidx = new int[1];
            transitLayer.patternsForStop.get(stopIdx).forEach(p -> {
                patternidx[0] = p;
                return false;
            });

            com.conveyal.r5.transit.TripPattern pattern = transitLayer.tripPatterns.get(patternidx[0]);
            RouteInfo routeInfo = transitLayer.routes.get(pattern.routeIndex);
            mode = TransitLayer.getTransitModes(routeInfo.route_type);
        }
    }
}
