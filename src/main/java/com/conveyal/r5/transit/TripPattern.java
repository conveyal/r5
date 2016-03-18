package com.conveyal.r5.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * This is like a transmodel JourneyPattern.
 * All the trips on the same Route that have the same sequence of stops, with the same pickup/dropoff options.
 */
public class TripPattern implements Serializable, Cloneable {

    private static Logger LOG = LoggerFactory.getLogger(TripPattern.class);

    /**
     * This is the ID of this trip pattern _in the original transport network_. This is important because if it were the
     * ID in this transport network the ID would depend on the order of application of scenarios, and because this ID is
     * used to map results back to the original network.
     */
    public int originalId;

    public String routeId;
    public int directionId = Integer.MIN_VALUE;
    public int[] stops;
    // Could be compacted into 2 bits each or a bunch of flags, but is it even worth it?
    public PickDropType[] pickups;
    public PickDropType[] dropoffs;
    public BitSet wheelchairAccessible; // One bit per stop
    public List<TripSchedule> tripSchedules = new ArrayList<>();

    /** does this trip pattern have any frequency trips */
    public boolean hasFrequencies;

    /** does this trip pattern have any scheduled trips */
    public boolean hasSchedules;

    // This set includes the numeric codes for all services on which at least one trip in this pattern is active.
    public BitSet servicesActive = new BitSet();

    /** index of this route in TransitLayer data. -1 if detailed route information has not been loaded */
    public int routeIndex = -1;

    public TripPattern (TripPatternKey tripPatternKey) {
        int nStops = tripPatternKey.stops.size();
        stops = new int[nStops];
        pickups = new PickDropType[nStops];
        dropoffs = new PickDropType[nStops];
        wheelchairAccessible = new BitSet(nStops);
        for (int s = 0; s < nStops; s++) {
            stops[s] = tripPatternKey.stops.get(s);
            pickups[s] = PickDropType.forGtfsCode(tripPatternKey.pickupTypes.get(s));
            dropoffs[s] = PickDropType.forGtfsCode(tripPatternKey.dropoffTypes.get(s));
        }
        routeId = tripPatternKey.routeId;
    }

    public void addTrip (TripSchedule tripSchedule) {
        tripSchedules.add(tripSchedule);
        hasFrequencies = hasFrequencies || tripSchedule.headwaySeconds != null;
        hasSchedules = hasSchedules || tripSchedule.headwaySeconds == null;
        servicesActive.set(tripSchedule.serviceCode);
    }

    public void setOrVerifyDirection (int directionId) {
        if (this.directionId != directionId) {
            if (this.directionId == Integer.MIN_VALUE) {
                this.directionId = directionId;
                LOG.debug("Pattern has route_id {} and direction_id {}", routeId, directionId);
            } else {
                LOG.warn("Trips with different direction IDs are in the same pattern.");
            }
        }
    }

    // Simply write "graph builder annotations" to a log file alongside the graphs.
    // function in gtfs-lib getOrderedStopTimes(string tripId)
    // Test GTFS loading on NL large data set.

    /**
     * Linear search.
     * @return null if no departure is possible.
     */
    TripSchedule findNextDeparture (int time, int stopOffset) {
        TripSchedule bestSchedule = null;
        int bestTime = Integer.MAX_VALUE;
        for (TripSchedule schedule : tripSchedules) {
            boolean active = servicesActive.get(schedule.serviceCode);
            // LOG.info("Trip with service {} active: {}.", schedule.serviceCode, active);
            if (active) {
                int departureTime = schedule.departures[stopOffset];
                if (departureTime > time && departureTime < bestTime) {
                    bestTime = departureTime;
                    bestSchedule = schedule;
                }
            }
        }
        return bestSchedule;
    }

    public TripPattern clone() {
        try {
            return (TripPattern) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TripPattern on route ");
        sb.append(routeId);
        sb.append(" with stops ");
        sb.append(Arrays.toString(stops));
        return sb.toString();
    }
}
