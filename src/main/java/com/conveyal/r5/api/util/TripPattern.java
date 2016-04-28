package com.conveyal.r5.api.util;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripFlag;
import com.conveyal.r5.transit.TripSchedule;

import java.util.ArrayList;
import java.util.List;

/**
 * Trip pattern information for API responses in GraphQL
 *
 * Getters are called automatically based on field names.
 */
public class TripPattern {
    //This is used to get stop information from transitLayer
    private final TransitLayer transitLayer;
    private final com.conveyal.r5.transit.TripPattern tripPattern;
    private final int tripPatternIdx;

    public TripPattern(TransitLayer transitLayer, int tripPatternIdx) {
        this.transitLayer = transitLayer;
        this.tripPattern = transitLayer.tripPatterns.get(tripPatternIdx);
        this.tripPatternIdx = tripPatternIdx;
    }

    /**
     * @return Index of this trip pattern in patterns array
     */
    public int getTripPatternIdx() {
        return tripPatternIdx;
    }

    /**
     * @return Direction ID of all trips in this trip pattern
     */
    public Integer getDirectionId() {
        return tripPattern.directionId == Integer.MIN_VALUE ? null: tripPattern.directionId;
    }

    /**
     * @return Index of route in route array
     */
    public Integer getRouteIdx() {
        return tripPattern.routeIndex < 0 ? null: tripPattern.routeIndex;
    }

    /**
     * @return GTFS route ID (Agency unique)
     */
    public String getRouteId() {
        return tripPattern.routeId;
    }

    public List<Stop> getStops() {
        List<Stop> stops = new ArrayList<>(tripPattern.stops.length);
        for(int i=0; i< tripPattern.stops.length; i++) {
            int stopIdx = tripPattern.stops[i];
            Stop stop = new Stop(stopIdx, transitLayer);
            stops.add(stop);
        }

        return stops;
    }

    public List<Trip> getTrips() {
        List<Trip> trips = new ArrayList<>(tripPattern.tripSchedules.size());
        for (TripSchedule tripSchedule: tripPattern.tripSchedules) {
            Trip trip = new Trip();
            trip.tripId = tripSchedule.tripId;
            trip.serviceId = transitLayer.services.get(tripSchedule.serviceCode).service_id;
            trip.bikesAllowed = tripSchedule.getFlag(TripFlag.BICYCLE);
            trip.wheelchairAccessible = tripSchedule.getFlag(TripFlag.WHEELCHAIR);
            trips.add(trip);
        }
        return trips;
    }
}
