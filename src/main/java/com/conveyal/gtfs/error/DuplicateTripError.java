package com.conveyal.gtfs.error;

import com.conveyal.gtfs.model.Trip;
import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 5/6/16.
 */
public class DuplicateTripError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final Priority priority = Priority.LOW;
    public final String duplicateTripId;
    public final String patternName;
    public final String routeId;
    String serviceId;
    String blockId;
    String firstDeparture;
    String lastArrival;

    public DuplicateTripError(Trip trip, long line, String duplicateTripId, String patternName, String firstDeparture, String lastArrival) {
        super("trips", line, "trip_id", trip.trip_id);
        this.duplicateTripId = duplicateTripId;
        this.patternName = patternName;
        this.routeId = trip.route_id;
        this.blockId = trip.block_id;
        this.serviceId = trip.service_id;
        this.firstDeparture = firstDeparture;
        this.lastArrival = lastArrival;
    }

    @Override public String getMessage() {
        return String.format("Trip Ids %s & %s (route %s) are duplicates (pattern: %s, calendar: %s, from %s to %s)", duplicateTripId, affectedEntityId, routeId, patternName, serviceId, firstDeparture, lastArrival);
    }
}
