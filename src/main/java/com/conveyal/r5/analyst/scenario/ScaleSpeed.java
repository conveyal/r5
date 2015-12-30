package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.PickDropType;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.primitives.Booleans;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Scale travel times by a constant factor over the whole length of trips.
 * That is, uniformly speed trips up or slow them down.
 */
public class ScaleSpeed extends TripPatternModification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ScaleSpeed.class);

    /** The multiplicative scale factor for travel times. */
    public double scaleFactor = 1;

    /** Which stop in the route serves as the fixed point around which trips are contracted or expanded. */
    // public int referenceStop = 0;

    @Override
    public String getType() {
        return "scale-speed";
    }

    @Override
    public TripPattern applyToTripPattern(TripPattern originalTripPattern) {
        if (!couldMatch(originalTripPattern)) {
            return originalTripPattern;
        }
        if (!routeId.contains(originalTripPattern.routeId)) {
            return originalTripPattern;
        }
        TripPattern pattern = originalTripPattern.clone();
        pattern.tripSchedules = pattern.tripSchedules.stream()
                .map(ts -> this.applyToTripSchedule(ts))
                .collect(Collectors.toList());
        LOG.info("Scaled travel times (factor {}) for all trips on {}.", scaleFactor, originalTripPattern);
        return pattern;
    }

    private TripSchedule applyToTripSchedule (TripSchedule originalSchedule) {
        int nStops = originalSchedule.getNStops();
        int firstArrival = originalSchedule.arrivals[0];
        TripSchedule schedule = originalSchedule.clone();
        schedule.arrivals = new int[nStops];
        schedule.departures = new int[nStops];
        for (int i = 0; i < nStops; i++) {
            int relativeArrival = originalSchedule.arrivals[i] - firstArrival;
            int relativeDeparture = originalSchedule.departures[i] - firstArrival;
            schedule.arrivals[i] = (int) Math.round(firstArrival + (relativeArrival * scaleFactor));
            schedule.departures[i] = (int) Math.round(firstArrival + (relativeDeparture * scaleFactor));
        }
        int originalTravelTime = originalSchedule.departures[nStops - 1] - originalSchedule.arrivals[0];
        int updatedTravelTime = schedule.departures[nStops - 1] - schedule.arrivals[0];
        LOG.info("Total travel time on trip {} changed from {} to {} seconds.",
                schedule.tripId, originalTravelTime, updatedTravelTime);
        return schedule;
    }

}
