package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.RemoveTrip;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;


/**
 * A key simplifying factor is that we don't handle overnight trips. This is fine for analysis at usual times of day.
 */
public class TransitLayer implements Serializable, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(TransitLayer.class);

    // Do we really need to store this? It does serve as a key into the GTFS MapDB.
    public List<String> stopIdForIndex = new ArrayList<>();

    public static final int TYPICAL_NUMBER_OF_STOPS_PER_TRIP = 30;

    public List<TripPattern> tripPatterns = new ArrayList<>();

    // TODO bidirectional indexes between vertex and stop are in transitLayer, so the linkage between street and transit layers is really one-to-many, not bidirectional. Use this fact to add multiple GTFS.

    // Maybe we need a StopStore that has (streetVertexForStop, transfers, flags, etc.)
    public TIntList streetVertexForStop = new TIntArrayList();

    // Inverse map of streetVertexForStop, and reconstructed from that list.
    public transient TIntIntMap stopForStreetVertex;

    // For each stop, a packed list of transfers to other stops
    public List<TIntList> transfersForStop;

    /** Information about a route */
    public List<RouteInfo> routes = new ArrayList<>();

    /** The names of the stops */
    public List<String> stopNames = new ArrayList<>();

    public List<TIntList> patternsForStop;

    public List<Service> services = new ArrayList<>();

    // TODO there is probably a better way to do this, but for now we need to retain stop object for linking to streets
    public transient List<Stop> stopForIndex = new ArrayList<>();

    // The coordinates of a place roughly in the center of the transit network, for centering maps and coordinate systems.
    public double centerLon;
    public double centerLat;

    /** does this TransitLayer have any frequency-based trips? */
    public boolean hasFrequencies = false;

    /** Does this TransitLayer have any schedules */
    public boolean hasSchedules = false;

    public int nTrips = 0;

    /** this is the result of running a search from every stop in the graph, map from stop vertex index to (vertex index, distance) */
    public transient TIntIntMap[] stopTree;

    /**
     * A transitLayer can only be linked to one StreetLayer, otherwise the street indexes for the transit stops would
     * be ambiguous. It can however be linked to no StreetLayer. So if this field is null there are no known streets,
     * but if this field is set then this TransitLayer has already been linked to a StreetLayer.
     * This field is only public because it has to be set from StreetLayer, which is in another package.
     */
    public StreetLayer linkedStreetLayer = null;

    /** Load a GTFS feed with full load level */
    public void loadFromGtfs (GTFSFeed gtfs) {
        loadFromGtfs(gtfs, LoadLevel.FULL);
    }

    /**
     * Load data from a GTFS feed. Call multiple times to load multiple feeds.
     */
    public void loadFromGtfs (GTFSFeed gtfs, LoadLevel level) {

        // Load stops.
        // ID is the GTFS string ID, stopIndex is the zero-based index, stopVertexIndex is the index in the street layer.
        TObjectIntMap<String> indexForStopId = new TObjectIntHashMap<>();
        for (Stop stop : gtfs.stops.values()) {
            int stopIndex = stopIdForIndex.size();
            indexForStopId.put(stop.stop_id, stopIndex);
            stopIdForIndex.add(stop.stop_id);
            stopForIndex.add(stop);

            if (level == LoadLevel.FULL) {
                stopNames.add(stop.stop_name);
            }
        }

        // Load service periods, assigning integer codes which will be referenced by trips and patterns.
        TObjectIntMap<String> serviceCodeNumber = new TObjectIntHashMap<>(20, 0.5f, -1);
        gtfs.services.forEach((serviceId, service) -> {
            int serviceIndex = services.size();
            services.add(service);
            serviceCodeNumber.put(serviceId, serviceIndex);
            LOG.debug("Service {} has ID {}", serviceIndex, serviceId);
        });

        // Group trips by stop pattern (including pickup/dropoff type) and fill stop times into patterns.
        // Also group trips by the blockId they belong to, and chain them together if they allow riders to stay on board
        // the vehicle from one trip to the next, even if it changes routes or directions. This is called "interlining".

        LOG.info("Grouping trips by stop pattern and block, and creating trip schedules.");
        // These are temporary maps used only for grouping purposes.
        Map<TripPatternKey, TripPattern> tripPatternForStopSequence = new HashMap<>();
        Multimap<String, TripSchedule> tripsForBlock = HashMultimap.create();
        TObjectIntMap<Route> routeIndexForRoute = new TObjectIntHashMap<>();
        int nTripsAdded = 0;
        for (String tripId : gtfs.trips.keySet()) {
            Trip trip = gtfs.trips.get(tripId);
            // Construct the stop pattern and schedule for this trip
            // Should we really be resolving to an object reference for Route?
            // That gets in the way of GFTS persistence.
            TripPatternKey tripPatternKey = new TripPatternKey(trip.route.route_id);
            TIntList arrivals = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList departures = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            for (StopTime st : gtfs.getOrderedStopTimesForTrip(tripId)) {
                tripPatternKey.addStopTime(st, indexForStopId);
                arrivals.add(st.arrival_time);
                departures.add(st.departure_time);
            }
            TripPattern tripPattern = tripPatternForStopSequence.get(tripPatternKey);
            if (tripPattern == null) {
                tripPattern = new TripPattern(tripPatternKey);

                // if we haven't seen the route yet _from this feed_ (as IDs are only feed-unique)
                // create it.
                if (level == LoadLevel.FULL) {
                    if (!routeIndexForRoute.containsKey(trip.route)) {
                        int routeIndex = routes.size();
                        RouteInfo ri = new RouteInfo(trip.route);
                        routes.add(ri);
                        routeIndexForRoute.put(trip.route, routeIndex);
                    }

                    tripPattern.routeIndex = routeIndexForRoute.get(trip.route);
                }

                tripPatternForStopSequence.put(tripPatternKey, tripPattern);
                tripPatterns.add(tripPattern);
            }
            tripPattern.setOrVerifyDirection(trip.direction_id);
            int serviceCode = serviceCodeNumber.get(trip.service.service_id);

            // TODO there's no reason why we can't just filter trips like this, correct?
            // TODO this means that invalid trips still have empty patterns created
            TripSchedule tripSchedule = TripSchedule.create(trip, arrivals.toArray(), departures.toArray(), serviceCode);
            if (tripSchedule == null) continue;

            tripPattern.addTrip(tripSchedule);

            this.hasFrequencies = this.hasFrequencies || tripSchedule.headwaySeconds != null;
            this.hasSchedules = this.hasSchedules || tripSchedule.headwaySeconds == null;

            nTripsAdded += 1;
            // Record which block this trip belongs to, if any.
            if ( ! Strings.isNullOrEmpty(trip.block_id)) {
                tripsForBlock.put(trip.block_id, tripSchedule);
            }
        }
        LOG.info("Done creating {} trips on {} patterns.", nTripsAdded, tripPatternForStopSequence.size());

        LOG.info("Chaining trips together according to blocks to model interlining...");
        // Chain together trips served by the same vehicle that allow transfers by simply staying on board.
        // Elsewhere this is done by grouping by (serviceId, blockId) but this is not supported by the spec.
        // Discussion started on gtfs-changes.
        tripsForBlock.asMap().forEach((blockId, trips) -> {
            TripSchedule[] schedules = trips.toArray(new TripSchedule[trips.size()]);
            Arrays.sort(schedules); // Sorts on first departure time
            for (int i = 0; i < schedules.length - 1; i++) {
                schedules[i].chainTo(schedules[i + 1]);
            }
        });
        LOG.info("Done chaining trips together according to blocks.");

        LOG.info("Sorting trips on each pattern");
        for (TripPattern tripPattern : tripPatternForStopSequence.values()) {
            Collections.sort(tripPattern.tripSchedules);
        }
        LOG.info("done sorting");

        LOG.info("Finding the approximate center of the transport network...");
        findCenter(gtfs.stops.values());

        // Will be useful in naming patterns.
//        LOG.info("Finding topology of each route/direction...");
//        Multimap<T2<String, Integer>, TripPattern> patternsForRouteDirection = HashMultimap.create();
//        tripPatterns.forEach(tp -> patternsForRouteDirection.put(new T2(tp.routeId, tp.directionId), tp));
//        for (T2<String, Integer> routeAndDirection : patternsForRouteDirection.keySet()) {
//            RouteTopology topology = new RouteTopology(routeAndDirection.first, routeAndDirection.second, patternsForRouteDirection.get(routeAndDirection));
//        }

    }

    // The median of all stopTimes would be best but that involves sorting a huge list of numbers.
    // So we just use the mean of all stops for now.
    private void findCenter (Collection<Stop> stops) {
        double lonSum = 0;
        double latSum = 0;
        for (Stop stop : stops) {
            latSum += stop.stop_lat;
            lonSum += stop.stop_lon;
        }
        // Stops is a HashMap so size() is fast. If it ever becomes a MapDB BTree, we may want to do this differently.
        centerLat = latSum / stops.size();
        centerLon = lonSum / stops.size();
    }

    /** (Re-)build transient indexes of this TripPattern, connecting stops to patterns etc. */
    public void rebuildTransientIndexes () {

        // 1. Which patterns pass through each stop?
        // We could store references to patterns rather than indexes.
        int nStops = stopIdForIndex.size();
        patternsForStop = new ArrayList<>(nStops);
        for (int i = 0; i < nStops; i++) {
            patternsForStop.add(new TIntArrayList());
        }
        int p = 0;
        for (TripPattern pattern : tripPatterns) {
            for (int stopIndex : pattern.stops) {
                if (!patternsForStop.get(stopIndex).contains(p)) {
                    patternsForStop.get(stopIndex).add(p);
                }
            }
            p++;
        }

        // 2. What street vertex represents each transit stop? Invert the serialized map.
        stopForStreetVertex = new TIntIntHashMap(streetVertexForStop.size(), 0.5f, -1, -1);
        for (int s = 0; s < streetVertexForStop.size(); s++) {
            stopForStreetVertex.put(streetVertexForStop.get(s), s);
        }
    }

    public void buildStopTree () {

        LOG.info("Building stop trees (cached distances between transit stops and street intersections).");
        if (linkedStreetLayer == null) {
            throw new IllegalStateException("Attempt to build stop trees on a transit layer that is not linked to a street layer.");
        }

        // For each transit stop, an int->int map giving the distance of every reached street intersection from the origin stop.
        stopTree = new TIntIntMap[getStopCount()];

        StreetRouter r = new StreetRouter(linkedStreetLayer);
        r.distanceLimitMeters = 2000;

        for (int stop = 0; stop < getStopCount(); stop++) {
            int originVertex = streetVertexForStop.get(stop);

            if (originVertex == -1) {
                // -1 indicates that this stop is not linked to the street network.
                LOG.info("Stop {} has not been linked to the street network.", stop);
                stopTree[stop] = null;
                continue;
            }

            r.setOrigin(originVertex);
            r.route();

            stopTree[stop] = r.getReachedVertices();
        }
        LOG.info("Done building stop trees.");
    }

    public static TransitLayer fromGtfs (List<String> files) {
        TransitLayer transitLayer = new TransitLayer();

        for (String file : files) {
            GTFSFeed gtfs = GTFSFeed.fromFile(file);
            transitLayer.loadFromGtfs(gtfs);
        }

        return transitLayer;
    }

    public int getStopCount () {
        return stopIdForIndex.size();
    }

    // Mark all services that are active on the given day. Trips on inactive services will not be used in the search.
    public BitSet getActiveServicesForDate (LocalDate date) {
        BitSet activeServices = new BitSet();
        int s = 0;
        for (Service service : services) {
            if (service.activeOn(new org.joda.time.LocalDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth()))) {
                activeServices.set(s);
            }
            s++;
        }
        return activeServices;
    }

    // TODO setStreetLayer which automatically links and records the streetLayer ID in a field for use elsewhere?


    public TransitLayer clone() {
        try {
            return (TransitLayer) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /** How much information should we load/save? */
    public enum LoadLevel {
        /** Load only information required for analytics, leaving out route names, etc. */
        BASIC,
        /** Load enough information for customer facing trip planning */
        FULL
    }
}
