package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.conveyal.r5.api.util.TransitModes;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import com.conveyal.r5.streets.StreetRouter;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.*;


/**
 * A key simplifying factor is that we don't handle overnight trips. This is fine for analysis at usual times of day.
 */
public class TransitLayer implements Serializable, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(TransitLayer.class);

    //TransportNetwork timezone. It is read from GTFS agency. If it is invalid it is GMT
    protected ZoneId timeZone;

    // Do we really need to store this? It does serve as a key into the GTFS MapDB.
    // It contains information that is temporarily also held in stopForIndex.
    public List<String> stopIdForIndex = new ArrayList<>();

    // Inverse map of stopIdForIndex, reconstructed from that list (not serialized). No-entry value is -1.
    public transient TObjectIntMap<String> indexForStopId;

    // This is used as an initial size estimate for many lists.
    public static final int TYPICAL_NUMBER_OF_STOPS_PER_TRIP = 30;

    public List<TripPattern> tripPatterns = new ArrayList<>();

    // Maybe we need a StopStore that has (streetVertexForStop, transfers, flags, etc.)
    public TIntList streetVertexForStop = new TIntArrayList();

    // Inverse map of streetVertexForStop, and reconstructed from that list.
    public transient TIntIntMap stopForStreetVertex;

    // For each stop, a packed list of transfers to other stops
    public List<TIntList> transfersForStop = new ArrayList<>();

    /** Information about a route */
    public List<RouteInfo> routes = new ArrayList<>();

    /** The names of the stops */
    public List<String> stopNames = new ArrayList<>();

    public List<TIntList> patternsForStop;

    public List<Service> services = new ArrayList<>();

    /** If true at index stop allows boarding with wheelchairs **/
    public BitSet stopsWheelchair;

    // TODO there is probably a better way to do this, but for now we need to retain stop object for linking to streets
    public transient List<Stop> stopForIndex = new ArrayList<>();

    // The coordinates of a place roughly in the center of the transit network, for centering maps and coordinate systems.
    public double centerLon;
    public double centerLat;

    /** does this TransitLayer have any frequency-based trips? */
    public boolean hasFrequencies = false;

    /** Does this TransitLayer have any schedules */
    public boolean hasSchedules = false;

    /**
     * For each transit stop, an int->int map giving the distance of every reachable street intersection from the
     * origin stop. This is the result of running a distance-constrained street search from every stop in the graph.
     */
    public transient List<TIntIntMap> stopTrees;

    /**
     * The TransportNetwork containing this TransitLayer. This link up the object tree also allows us to access the
     * StreetLayer associated with this TransitLayer in the same TransportNetwork without maintaining bidirectional
     * references between the two layers.
     */
    public TransportNetwork parentNetwork = null;

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
        TObjectIntMap<String> indexForUnscopedStopId = new TObjectIntHashMap<>();
        stopsWheelchair = new BitSet(gtfs.stops.size());
        for (Stop stop : gtfs.stops.values()) {
            int stopIndex = stopIdForIndex.size();
            String scopedStopId = String.join(":", stop.feed_id, stop.stop_id);
            // This is only used while building the TransitNetwork to look up StopTimes from the same feed.
            indexForUnscopedStopId.put(stop.stop_id, stopIndex);
            stopIdForIndex.add(scopedStopId);
            stopForIndex.add(stop);
            if (stop.wheelchair_boarding != null && stop.wheelchair_boarding.trim().equals("1")) {
                stopsWheelchair.set(stopIndex);
            }
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
        TRIPS: for (String tripId : gtfs.trips.keySet()) {
            Trip trip = gtfs.trips.get(tripId);
            // Construct the stop pattern and schedule for this trip
            // Should we really be resolving to an object reference for Route?
            // That gets in the way of GFTS persistence.
            String scopedRouteId = String.join(":", trip.route.feed_id, trip.route.route_id);
            TripPatternKey tripPatternKey = new TripPatternKey(scopedRouteId);
            TIntList arrivals = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList departures = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList stopSequences = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);

            int previousDeparture = Integer.MIN_VALUE;

            int nStops = 0;

            Iterable<StopTime> stopTimes;

            try {
                stopTimes = gtfs.getInterpolatedStopTimesForTrip(tripId);
            } catch (GTFSFeed.FirstAndLastStopsDoNotHaveTimes e) {
                LOG.warn("First and last stops do not both have times specified on trip {} on route {}, skipping this as interpolation is impossible", trip.trip_id, trip.route.route_id);
                continue TRIPS;
            }

            for (StopTime st : stopTimes) {
                tripPatternKey.addStopTime(st, indexForUnscopedStopId);
                arrivals.add(st.arrival_time);
                departures.add(st.departure_time);
                stopSequences.add(st.stop_sequence);

                if (previousDeparture > st.arrival_time || st.arrival_time > st.departure_time) {
                    LOG.warn("Reverse travel at stop {} on trip {} on route {}, skipping this trip as it will wreak havoc with routing", st.stop_id, trip.trip_id, trip.route.route_id);
                    continue TRIPS;
                }

                if (previousDeparture == st.arrival_time) {
                    LOG.warn("Zero-length hop at stop {} on trip {} on route {} {}", st.stop_id, trip.trip_id, trip.route.route_id, trip.route.route_short_name);
                }

                previousDeparture = st.departure_time;

                nStops++;
            }

            if (nStops == 0) {
                LOG.warn("Trip {} on route {} has no stops, it will not be used", trip.trip_id, trip.route.route_id);
                continue;

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
                tripPattern.originalId = tripPatterns.size();
                tripPatterns.add(tripPattern);
            }
            tripPattern.setOrVerifyDirection(trip.direction_id);
            int serviceCode = serviceCodeNumber.get(trip.service.service_id);

            // TODO there's no reason why we can't just filter trips like this, correct?
            // TODO this means that invalid trips still have empty patterns created
            TripSchedule tripSchedule = TripSchedule.create(trip, arrivals.toArray(), departures.toArray(), stopSequences.toArray(), serviceCode);
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

        //Set transportNetwork timezone
        //If there are no agencies (which is strange) it is GMT
        //Otherwise it is set to first valid agency timezone and warning is shown if agencies have different timezones
        if (gtfs.agency.size() == 0) {
            timeZone = ZoneId.of("GMT");
            LOG.warn("graph contains no agencies; API request times will be interpreted as GMT.");
        } else {
            for (Agency agency : gtfs.agency.values()) {
                if (agency.agency_timezone == null) {
                    LOG.warn("Agency {} is without timezone", agency.agency_name);
                    continue;
                }
                ZoneId tz;
                try {
                    tz = ZoneId.of(agency.agency_timezone);
                } catch (ZoneRulesException z) {
                    LOG.error("Agency {} in GTFS with timezone '{}' wasn't found in timezone database reason: {}", agency.agency_name, agency.agency_timezone, z.getMessage());
                    //timezone will be set to GMT if it is still empty after for loop
                    continue;
                } catch (DateTimeException dt) {
                    LOG.error("Agency {} in GTFS has timezone in wrong format:'{}'. Expected format: area/city ", agency.agency_name, agency.agency_timezone);
                    //timezone will be set to GMT if it is still empty after for loop
                    continue;
                }
                //First time setting timezone
                if (timeZone == null) {
                    LOG.info("TransportNetwork time zone set to {} from agency '{}' and agency_timezone:{}", tz,
                        agency.agency_name, agency.agency_timezone);
                    timeZone = tz;
                } else if (!timeZone.equals(tz)) {
                    LOG.error("agency time zone {} differs from TransportNetwork time zone: {}. This will be problematic.", tz,
                        timeZone);
                }
            }

            //This can only happen if all agencies have empty timezones
            if (timeZone == null) {
                timeZone = ZoneId.of("GMT");
                LOG.warn(
                    "No agency in graph had valid timezone; API request times will be interpreted as GMT.");
            }
        }

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

        // 3. What is the integer index for each GTFS stop ID?
        indexForStopId = new TObjectIntHashMap<>(stopIdForIndex.size(), 0.5f, -1);
        for (int s = 0; s < stopIdForIndex.size(); s++) {
            indexForStopId.put(stopIdForIndex.get(s), s);
        }
    }

    /**
     * Run a distance-constrained street search from every transit stop in the graph.
     * Store the distance to every reachable street vertex for each of these origin stops.
     */
    public void buildStopTrees() {
        LOG.info("Building stop trees (cached distances between transit stops and street intersections).");
        // Allocate a new empty array of stop trees, releasing any existing ones.
        stopTrees = new ArrayList<>(getStopCount());
        for (int stop = 0; stop < getStopCount(); stop++) {
            buildOneStopTree(stop);
        }
        LOG.info("Done building stop trees.");
    }

    /**
     * Perform a single on-street search from the specified transit stop. Store the distance to every reached
     * street vertex.
     * @param stop the internal integer stop ID for which to build a stop tree.
     */
    public void buildOneStopTree(int stop) {
        // Lists do not auto-grow if you try to add an element past their end.
        // So until we need different behavior, we only support adding a stop tree to the end of the list,
        // not updating an existing one or adding one out past the end of the list.
        if (stopTrees.size() != stop) {
            throw new RuntimeException("New stop trees can only be added to the end of the list.");
        }

        int originVertex = streetVertexForStop.get(stop);
        if (originVertex == -1) {
            // -1 indicates that this stop is not linked to the street network.
            LOG.warn("Stop {} has not been linked to the street network, cannot build stop tree.", stop);
            stopTrees.add(null);
            return;
        }
        StreetRouter router = new StreetRouter(parentNetwork.streetLayer);
        router.distanceLimitMeters = 2000;
        router.setOrigin(originVertex);
        router.route();
        stopTrees.add(router.getReachedVertices());
    }

    public static TransitLayer fromGtfs (List<String> files) {
        TransitLayer transitLayer = new TransitLayer();

        for (String file : files) {
            GTFSFeed gtfs = GTFSFeed.fromFile(file);
            transitLayer.loadFromGtfs(gtfs);
            //Makes sure that temporary mapdb files are deleted after they aren't needed
            gtfs.close();
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
            if (service.activeOn(date)) {
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

    public static TransitModes getTransitModes(int routeType) {
        /* TPEG Extension  https://groups.google.com/d/msg/gtfs-changes/keT5rTPS7Y0/71uMz2l6ke0J */
        if (routeType >= 100 && routeType < 200){ // Railway Service
            return TransitModes.RAIL;
        }else if (routeType >= 200 && routeType < 300){ //Coach Service
            return TransitModes.BUS;
        }else if (routeType >= 300 && routeType < 500){ //Suburban Railway Service and Urban Railway service
            return TransitModes.RAIL;
        }else if (routeType >= 500 && routeType < 700){ //Metro Service and Underground Service
            return TransitModes.SUBWAY;
        }else if (routeType >= 700 && routeType < 900){ //Bus Service and Trolleybus service
            return TransitModes.BUS;
        }else if (routeType >= 900 && routeType < 1000){ //Tram service
            return TransitModes.TRAM;
        }else if (routeType >= 1000 && routeType < 1100){ //Water Transport Service
            return TransitModes.FERRY;
        }else if (routeType >= 1100 && routeType < 1200){ //Air Service
            throw new IllegalArgumentException("Air transport not supported" + routeType);
        }else if (routeType >= 1200 && routeType < 1300){ //Ferry Service
            return TransitModes.FERRY;
        }else if (routeType >= 1300 && routeType < 1400){ //Telecabin Service
            return TransitModes.GONDOLA;
        }else if (routeType >= 1400 && routeType < 1500){ //Funicalar Service
            return TransitModes.FUNICULAR;
        }else if (routeType >= 1500 && routeType < 1600){ //Taxi Service
            throw new IllegalArgumentException("Taxi service not supported" + routeType);
        }
        //Is this really needed?
        /**else if (routeType >= 1600 && routeType < 1700){ //Self drive
            return TransitModes.CAR;
        }*/
        /* Original GTFS route types. Should these be checked before TPEG types? */
        switch (routeType) {
        case 0:
            return TransitModes.TRAM;
        case 1:
            return TransitModes.SUBWAY;
        case 2:
            return TransitModes.RAIL;
        case 3:
            return TransitModes.BUS;
        case 4:
            return TransitModes.FERRY;
        case 5:
            return TransitModes.CABLE_CAR;
        case 6:
            return TransitModes.GONDOLA;
        case 7:
            return TransitModes.FUNICULAR;
        default:
            throw new IllegalArgumentException("unknown gtfs route type " + routeType);
        }
    }

    /**
     * @return a semi-shallow copy of this transit layer for use when applying scenarios.
     */
    public TransitLayer scenarioCopy(TransportNetwork newScenarioNetwork) {
        TransitLayer copy = this.clone();
        copy.parentNetwork = newScenarioNetwork;
        // Protectively copy all the lists that will be affected by adding new stops to the network
        // See: StopSpec.materializeOne()
        // We would really only need to do this for modifications that create new stops.
        copy.stopIdForIndex = new ArrayList<>(this.stopIdForIndex);
        copy.stopNames = new ArrayList<>(this.stopNames);
        copy.streetVertexForStop = new TIntArrayList(this.streetVertexForStop);
        copy.stopTrees = new ArrayList<>(this.stopTrees);
        copy.transfersForStop = new ArrayList<>(this.transfersForStop);
        return copy;
    }

}
