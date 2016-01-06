package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.BitSet;

/**
 * A Filter that removes any trips that are not running in a given time window.
 * This reduces the size of the timetables before the search.
 */
public class InactiveTripsFilter extends TripScheduleModification {

    BitSet activeServices;
    int fromTime;
    int toTime;

    public InactiveTripsFilter (TransportNetwork network, ProfileRequest request) {
        if (network.transitLayer != null) {
            activeServices = network.transitLayer.getActiveServicesForDate(request.date);
            fromTime = request.fromTime;
            // The supplied time window is the departure time window.
            // Account for the fact that arrivals can be up to two hours later.
            toTime = request.toTime + (60 * 60 * 2);
        }
    }

    @Override
    public String getType() {
        // This modification type is used internally and should never be serialized.
        return "inactive-trips";
    }

    @Override
    public TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        if (activeServices == null) {
            return originalTransitLayer;
        }
        return super.applyToTransitLayer(originalTransitLayer);
    }

    /**
     * For each trip pattern, if that trip pattern is used on any of the service schedules that are active on
     * the date in question, apply the filter to each trip schedule individually. Otherwise reject the entire pattern,
     * it will not be used in the search.
     */
    @Override
    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        if (activeServices.intersects(originalTripPattern.servicesActive)) {
            return super.applyToTripPattern(originalTripPattern);
        } else {
            return null;
        }
    }


    /**
     * For each trip within a pattern, check whether that trip occurs on the supplied date. If so, keep the trip. If
     * not, return null eliminating it from the TransportNetwork that will be used in the search.
     */
    @Override
    public TripSchedule applyToTripSchedule(TripPattern tripPattern, TripSchedule tripSchedule) {
        if (activeServices.get(tripSchedule.serviceCode) && tripSchedule.overlapsTimeRange(fromTime, toTime)) {
            return tripSchedule;
        } else {
            return null;
        }
    }

}
